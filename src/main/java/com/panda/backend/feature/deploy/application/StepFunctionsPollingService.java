package com.panda.backend.feature.deploy.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panda.backend.feature.connect.entity.AwsConnection;
import com.panda.backend.feature.deploy.dto.MonitorCloudWatchResponse;
import com.panda.backend.feature.deploy.dto.DeploymentResult;
import com.panda.backend.feature.deploy.event.DeploymentEventPublisher;
import com.panda.backend.feature.deploy.event.DeploymentEventStore;
import com.panda.backend.feature.deploy.infrastructure.ExecutionArnStore;
import com.panda.backend.feature.deploy.infrastructure.DeploymentResultStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Step Functions의 실행 상태를 주기적으로 폴링하고
 * 상태 변화를 감지하여 SSE 이벤트로 발행하는 서비스
 *
 * 흐름:
 * 1. ECR 푸시 직후 startPollingAsync(deploymentId) 호출
 * 2. Secrets Manager에서 ExecutionArn 조회 (3초 대기 후)
 * 3. 2초마다 GetExecutionHistory API 호출
 * 4. 상태 변화 감지 시 SSE 이벤트 발행
 * 5. SUCCEEDED/FAILED 상태 도달 시 폴링 중단 및 정리
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StepFunctionsPollingService {

    private final SfnClient sfnClient;
    private final ExecutionArnStore executionArnStore;
    private final DeploymentEventPublisher eventPublisher;
    private final DeploymentEventStore deploymentEventStore;
    private final ObjectMapper objectMapper;
    private final MonitorCloudWatchService monitorCloudWatchService;
    private final EcsServiceUrlResolverService ecsServiceUrlResolverService;
    private final HealthCheckService healthCheckService;
    private final DeploymentResultStore deploymentResultStore;

    @Value("${aws.step-functions.polling-interval-ms:2000}")
    private long pollingIntervalMs;

    @Value("${aws.step-functions.max-polling-duration-ms:1800000}")
    private long maxPollingDurationMs;

    @Value("${aws.step-functions.wait-for-execution-arn-ms:10000}")
    private long waitForExecutionArnMs;

    @Value("${aws.step-functions.stale-event-timeout-ms:120000}")
    private long staleEventTimeoutMs;

    @Value("${aws.lambda.monitor-interval-seconds:30}")
    private long monitorIntervalSeconds;

    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    /**
     * ✅ 폴링 결과를 반환하는 내부 클래스
     * - currentStage: 현재 단계
     * - lastEventId: 처리한 마지막 이벤트 ID (다음 폴링에서 중복 제거용)
     */
    private static class PollingResult {
        String currentStage;
        long lastEventId;

        PollingResult(String currentStage, long lastEventId) {
            this.currentStage = currentStage;
            this.lastEventId = lastEventId;
        }
    }

    /**
     * 비동기로 Step Functions 폴링 시작
     * ECR 푸시 완료 직후 호출되어야 함
     *
     * @param deploymentId 배포 ID
     * @param owner GitHub owner
     * @param repo GitHub repo
     * @param awsConnection 사용자 AWS 연결 정보 (CloudWatch 모니터링용)
     */
    public void startPollingAsync(String deploymentId, String owner, String repo, AwsConnection awsConnection) {
        executorService.submit(() -> {
            try {
                pollExecutionHistory(deploymentId, owner, repo, awsConnection);
            } catch (Exception e) {
                log.error("Polling failed for deploymentId: {}", deploymentId, e);
                eventPublisher.publishErrorEvent(deploymentId,
                    "Step Functions 모니터링 오류: " + e.getMessage());
            }
        });

        log.info("Step Functions polling started asynchronously for deploymentId: {} ({}/{})", deploymentId, owner, repo);
    }

    /**
     * ExecutionHistory를 폴링하여 상태 변화 감지
     *
     * @param deploymentId 배포 ID
     * @param owner GitHub owner
     * @param repo GitHub repo
     * @param awsConnection 사용자 AWS 연결 정보
     */
    private void pollExecutionHistory(String deploymentId, String owner, String repo, AwsConnection awsConnection) {
        long pollingStartTime = System.currentTimeMillis();
        long lastMonitoringTime = System.currentTimeMillis();
        String executionArn = null;
        String previousStage = null;
        long lastProcessedEventId = 0L;  // ✅ 마지막 처리한 이벤트 ID 추적
        int pollCount = 0;
        int eventCount = 0;
        String secretName = "panda/stepfunctions/" + owner.toLowerCase() + "-" + repo.toLowerCase() + "-latest-execution";
        String branch = "main";  // Default branch

        // ✅ CheckDeployment 자동 완료용 타이머
        long checkDeploymentDetectedTime = -1;  // CheckDeployment 감지 시간
        final long AUTO_WAIT_DURATION_MS = 3 * 60 * 1000;  // 3분

        // CloudWatch 모니터링용 컨텍스트
        Map<String, Object> monitoringContext = new HashMap<>();

        try {
            // Step 1: Secrets Manager에서 ExecutionArn 조회
            log.info("⏳ [POLLING-START] deploymentId: {}, owner: {}, repo: {} - Waiting {}ms for ExecutionArn to be saved in Secrets Manager...",
                deploymentId, owner, repo, waitForExecutionArnMs);
            log.info("   Expected Secret Name: {}", secretName);
            Thread.sleep(waitForExecutionArnMs);

            log.info("🔍 [SECRETS-MANAGER-LOOKUP] deploymentId: {} - Attempting to retrieve ExecutionArn...", deploymentId);
            executionArn = executionArnStore.get(owner, repo);

            if (executionArn == null) {
                String errorMsg = "ExecutionArn not found in Secrets Manager after waiting";
                log.error("❌ [POLLING-FAILED] deploymentId: {}, owner: {}, repo: {} - {} (Secret may not have been created)",
                    deploymentId, owner, repo, errorMsg);
                log.error("   This means:");
                log.error("   1. EventBridge 규칙이 트리거되지 않음");
                log.error("   2. Step Functions이 실행되지 않음");
                log.error("   3. Lambda 함수가 ExecutionArn을 저장하지 않음");
                log.error("   ➜ AWS Console에서 다음을 확인하세요:");
                log.error("     - EventBridge 규칙: softbank-ecr-trigger-{}-{}", owner, repo);
                log.error("     - Secrets Manager Secret: {}", secretName);
                log.error("     - Step Functions: 실행 이력");
                log.error("     - Lambda: lambda_0_register_to_eventbus 로그");

                // 상세정보와 함께 에러 발행
                Map<String, Object> errorDetails = Map.of(
                    "errorCode", "EXECUTION_ARN_NOT_FOUND",
                    "errorMessage", errorMsg,
                    "deploymentId", deploymentId,
                    "owner", owner,
                    "repo", repo,
                    "secretName", secretName,
                    "suggestion", "EventBridge 규칙, Step Functions 실행, Lambda 로그를 확인하세요",
                    "timestamp", java.time.LocalDateTime.now().toString()
                );
                eventPublisher.publishErrorEvent(deploymentId, errorMsg, errorDetails);
                return;
            }

            log.info("✅ [EXECUTION-ARN-FOUND] deploymentId: {}, owner: {}, repo: {} - ExecutionArn: {}",
                deploymentId, owner, repo, executionArn);
            log.info("🚀 [POLLING-STARTED] deploymentId: {} - Starting Step Functions history polling...", deploymentId);

            // Step 2: ExecutionHistory 폴링 (최대 30분)
            long lastNewEventTime = System.currentTimeMillis();  // ✅ 마지막 새 이벤트 도착 시간
            while (System.currentTimeMillis() - pollingStartTime < maxPollingDurationMs) {
                pollCount++;
                long pollStartTime = System.currentTimeMillis();

                try {
                    // GetExecutionHistory API 호출 (includeExecutionData=true로 Task output 포함)
                    GetExecutionHistoryResponse history = sfnClient.getExecutionHistory(
                        GetExecutionHistoryRequest.builder()
                            .executionArn(executionArn)
                            .includeExecutionData(true)  // ✅ Task output 데이터 포함
                            .build()
                    );

                    // ✅ 현재 stage 분석 (마지막 처리한 이벤트 ID 이후의 이벤트만 처리)
                    PollingResult pollingResult = analyzeExecutionHistoryWithContext(
                        deploymentId,
                        history.events(),
                        monitoringContext,
                        awsConnection,
                        lastProcessedEventId
                    );

                    String currentStage = pollingResult.currentStage;
                    long previousLastEventId = lastProcessedEventId;
                    lastProcessedEventId = pollingResult.lastEventId;  // ✅ 마지막 이벤트 ID 업데이트

                    // ✅ 새 이벤트가 도착했으면 타이머 리셋
                    if (lastProcessedEventId > previousLastEventId) {
                        lastNewEventTime = System.currentTimeMillis();
                        log.debug("New events received - lastEventId: {} (was: {})", lastProcessedEventId, previousLastEventId);
                    }

                    // 현재 실행 상태 상세 로깅
                    long apiCallElapsedMs = System.currentTimeMillis() - pollStartTime;
                    int totalEventCount = history.events() != null ? history.events().size() : 0;
                    long lastEventTimestamp = 0;
                    String lastEventType = "";
                    if (history.events() != null && !history.events().isEmpty()) {
                        Object lastEvent = history.events().get(0); // 가장 최신 이벤트
                        if (lastEvent instanceof HistoryEvent) {
                            HistoryEvent he = (HistoryEvent) lastEvent;
                            lastEventTimestamp = he.timestamp() != null ? he.timestamp().getEpochSecond() : 0;
                            lastEventType = he.typeAsString() != null ? he.typeAsString() : "";
                        }
                    }

                    // Step Functions 실행 상태 정보 출력 (30초마다 또는 상태 변화 시)
                    if (pollCount % 15 == 1 || pollCount == 1) {  // 2초 간격이므로 약 30초마다
                        long totalElapsedSeconds = (System.currentTimeMillis() - pollingStartTime) / 1000;
                        long lastEventAgoSeconds = (System.currentTimeMillis() / 1000) - lastEventTimestamp;
                        log.info("📊 [Polling-Status] Poll #{}, deploymentId: {}, currentStage: {}, " +
                            "lastEventId: {} (type: {}), totalEvents: {}, totalElapsed: {}s, lastEventAgo: {}s",
                            pollCount, deploymentId, currentStage, lastProcessedEventId, lastEventType,
                            totalEventCount, totalElapsedSeconds, lastEventAgoSeconds);
                    }

                    log.debug("Poll #{} - deploymentId: {}, stage: {}, lastEventId: {}, totalEvents: {}, lastEventType: {}, apiCallElapsed: {}ms",
                        pollCount, deploymentId, currentStage, lastProcessedEventId, totalEventCount, lastEventType, apiCallElapsedMs);

                    // 상태 변화 감지 및 모니터링 정보 저장
                    if (!Objects.equals(currentStage, previousStage)) {
                        log.info("Stage changed: {} → {}", previousStage, currentStage);
                        eventPublisher.publishStepFunctionsProgress(deploymentId, currentStage);
                        previousStage = currentStage;
                        eventCount++;
                    }

                    // ✅ CheckDeployment 감지 후 타이머 시작
                    if ("DEPLOYMENT_READY".equals(currentStage) && checkDeploymentDetectedTime == -1) {
                        checkDeploymentDetectedTime = System.currentTimeMillis();
                        log.info("🔄 [AutoDeploy-3min] CheckDeployment 감지! 3분 자동 대기 시작 - deploymentId: {}", deploymentId);
                    }

                    // ✅ CheckDeployment 감지 후 3분 경과 확인
                    if ("DEPLOYMENT_READY".equals(currentStage) && checkDeploymentDetectedTime != -1) {
                        long elapsedMs = System.currentTimeMillis() - checkDeploymentDetectedTime;
                        long remainingMs = AUTO_WAIT_DURATION_MS - elapsedMs;

                        log.info("⏳ [AutoDeploy-3min-Countdown] CheckDeployment 감지 후 경과: {}ms/{}, 남은 시간: {}초",
                            elapsedMs, AUTO_WAIT_DURATION_MS, remainingMs / 1000);

                        // 3분이 지났으면 자동 완료
                        if (elapsedMs >= AUTO_WAIT_DURATION_MS) {
                            log.info("✅ [AutoDeploy-3min-Complete] 3분 경과! 자동으로 DEPLOYMENT_READY 상태로 저장 - deploymentId: {}", deploymentId);

                            // 배포 준비 완료 상태로 저장 (수동 전환 대기)
                            saveDeploymentReadyResult(deploymentId, owner, repo, branch,
                                monitoringContext, pollingStartTime, eventCount, awsConnection);

                            // ✅ SSE 연결 종료 신호: success 이벤트 발행
                            deploymentEventStore.sendDoneEvent(deploymentId, "✅ 3분 자동 대기 완료! DEPLOYMENT_READY 상태로 저장됨. /api/v1/deploy/{deploymentId}/switch를 호출하여 트래픽 전환을 진행하세요");
                            break;  // ✅ 폴링 종료
                        }
                    }

                    // Stage 4 완료 시 배포 완료 (RegisterTaskAndDeploy 만 완료)
                    if ("REGISTER_TASK_COMPLETED".equals(currentStage)) {
                        log.debug("RegisterTaskAndDeploy completed, waiting for CheckDeployment...");
                        // 계속 폴링 진행 (CheckDeployment 응답을 기다림)
                    }

                    // 완료/실패 시 폴링 종료
                    if ("SUCCEEDED".equals(currentStage) || "FAILED".equals(currentStage)) {
                        log.info("Polling completed for deploymentId: {}, final stage: {}", deploymentId, currentStage);

                        // 최종 결과 저장
                        saveFinalDeploymentResult(deploymentId, owner, repo, branch, currentStage,
                            monitoringContext, pollingStartTime, eventCount);
                        break;
                    }

                    // ✅ Stale Event 체크: 새 이벤트가 도착하지 않은 지 너무 오래된 경우 → DEPLOYMENT_READY로 변경
                    long timeSinceLastNewEvent = System.currentTimeMillis() - lastNewEventTime;
                    if (timeSinceLastNewEvent > staleEventTimeoutMs && lastProcessedEventId > 0) {
                        log.warn("⏳ [StaleEvent-Detected] Step Functions execution appears to be stuck - no new events for {}ms, lastEventId: {}, deploymentId: {}",
                            timeSinceLastNewEvent, lastProcessedEventId, deploymentId);
                        log.info("✅ [StaleEvent-AutoReady] Stale Event 감지! DEPLOYMENT_READY 상태로 자동 변경하여 /api/v1/deploy/{}/switch 호출 준비 - deploymentId: {}",
                            deploymentId, deploymentId);

                        // ✅ Stale Event 감지 시 DEPLOYMENT_READY 상태로 저장 (수동 전환 준비)
                        saveDeploymentReadyResult(deploymentId, owner, repo, branch,
                            monitoringContext, pollingStartTime, eventCount, awsConnection);

                        // ✅ SSE 연결 종료 신호
                        deploymentEventStore.sendDoneEvent(deploymentId,
                            "⏳ CheckDeployment 진행 중: " + (staleEventTimeoutMs / 1000) + "초 이상 응답이 없어 자동으로 DEPLOYMENT_READY 상태로 변경. /api/v1/deploy/{deploymentId}/switch를 호출하여 트래픽 전환을 진행하세요");
                        break;
                    }

                    // 타임아웃 체크: 최대 폴링 시간 초과
                    long elapsedMs = System.currentTimeMillis() - pollingStartTime;
                    if (elapsedMs > maxPollingDurationMs) {
                        log.error("Step Functions polling exceeded maximum duration for deploymentId: {}", deploymentId);
                        String errorMsg = String.format("Step Functions polling timeout: exceeded %d minutes",
                            maxPollingDurationMs / (60 * 1000));

                        // 상세정보와 함께 에러 발행
                        Map<String, Object> errorDetails = Map.of(
                            "errorCode", "POLLING_TIMEOUT",
                            "errorMessage", errorMsg,
                            "deploymentId", deploymentId,
                            "elapsedMs", elapsedMs,
                            "maxDurationMs", maxPollingDurationMs,
                            "pollCount", pollCount,
                            "suggestion", "배포가 너무 오래 진행 중입니다. AWS Step Functions를 확인하세요.",
                            "timestamp", java.time.LocalDateTime.now().toString()
                        );
                        eventPublisher.publishErrorEvent(deploymentId, errorMsg, errorDetails);

                        // 타임아웃 결과 저장
                        saveTimeoutResult(deploymentId, owner, repo, branch, pollingStartTime, eventCount,
                            "Step Functions 모니터링 타임아웃");
                        break;
                    }

                    // 폴링 간격 대기
                    Thread.sleep(pollingIntervalMs);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Polling interrupted for deploymentId: {}", deploymentId);
                    break;
                } catch (Exception e) {
                    log.error("Error during polling (poll #{}), retrying...", pollCount, e);
                    // 에러 발생 시에도 계속 폴링 시도
                    Thread.sleep(pollingIntervalMs);
                }
            }

            log.info("Polling finished - deploymentId: {}, totalPolls: {}", deploymentId, pollCount);

        } catch (Exception e) {
            log.error("Critical error in polling for deploymentId: {}", deploymentId, e);
            String errorMsg = "Step Functions 모니터링 중 오류 발생: " + e.getMessage();

            // 상세정보와 함께 에러 발행
            Map<String, Object> errorDetails = Map.of(
                "errorCode", "POLLING_ERROR",
                "errorMessage", errorMsg,
                "exceptionType", e.getClass().getSimpleName(),
                "exceptionMessage", e.getMessage() != null ? e.getMessage() : "Unknown error",
                "deploymentId", deploymentId,
                "timestamp", java.time.LocalDateTime.now().toString()
            );
            eventPublisher.publishErrorEvent(deploymentId, errorMsg, errorDetails);
        } finally {
            // 배포 완료 후 Secrets Manager에서 정리
            if (executionArn != null) {
                try {
                    executionArnStore.remove(owner, repo);
                } catch (Exception e) {
                    log.warn("Failed to clean up ExecutionArn for deploymentId: {}, owner: {}, repo: {}",
                        deploymentId, owner, repo, e);
                }
            }
        }
    }

    /**
     * ExecutionHistory Events를 분석하여 현재 Stage 파악 및 SSE 이벤트 발행
     *
     * Step Functions의 State 이름:
     * - EnsureInfra: 인프라 점검 및 생성 (Stage 3)
     * - RegisterTaskAndDeploy: Task Definition 재정의 및 CodeDeploy 시작 (Stage 4)
     * - CheckDeployment: 배포 상태 확인 (Stage 5)
     * - DeploymentSucceeded: 성공 (Stage 6)
     * - DeploymentFailed: 실패
     *
     * @param deploymentId 배포 ID
     * @param events ExecutionHistory Events (HistoryEvent 리스트)
     * @return 현재 Stage 이름
     */
    private String analyzeExecutionHistory(String deploymentId, List<?> events) {
        if (events == null || events.isEmpty()) {
            return "RUNNING";
        }

        try {
            // 역순으로 탐색 (최신 이벤트부터 확인)
            for (int i = events.size() - 1; i >= 0; i--) {
                Object eventObj = events.get(i);
                HistoryEvent event = castToHistoryEvent(eventObj);

                if (event == null) {
                    continue;
                }

                log.debug("Event #{}: type={}", i, event.typeAsString());

                // ExecutionFailed 체크
                if (event.typeAsString() != null && event.typeAsString().equals("ExecutionFailed")) {
                    log.warn("Execution failed for deploymentId: {}", deploymentId);
                    publishStageEvent(deploymentId, 6, "배포 실패");
                    return "FAILED";
                }

                // ExecutionSucceeded 체크
                if (event.typeAsString() != null && event.typeAsString().equals("ExecutionSucceeded")) {
                    log.info("Execution succeeded for deploymentId: {}", deploymentId);
                    publishStageEvent(deploymentId, 6, "배포 완료", Map.of("finalService", "green"));
                    return "SUCCEEDED";
                }

                // TaskStateEntered 이벤트 (Task 시작)
                if (event.typeAsString() != null && event.typeAsString().equals("TaskStateEntered")) {
                    String stage = analyzeTaskStateEntered(deploymentId, event);
                    if (stage != null) {
                        return stage;
                    }
                }

            }
        } catch (Exception e) {
            log.error("Error analyzing execution history for deploymentId: {}", deploymentId, e);
        }

        return "RUNNING";
    }

    /**
     * TaskStateEntered 이벤트 분석
     * Task가 시작될 때 호출되는 이벤트
     *
     * Step Functions 이벤트 구조:
     * {
     *   "timestamp": 1672531200.456,
     *   "type": "TaskStateEntered",
     *   "id": 2,
     *   "previousEventId": 1,
     *   "stateEnteredEventDetails": {
     *     "name": "EnsureInfra"
     *   }
     * }
     */
    private String analyzeTaskStateEntered(String deploymentId, HistoryEvent event) {
        try {
            // Step Functions 이벤트에서 stateEnteredEventDetails.name 추출
            String taskName = extractStateNameFromTaskEvent(event);

            log.debug("TaskStateEntered - taskName: {}", taskName);

            if (taskName == null) {
                return null;
            }

            // Stage 3: EnsureInfra
            if ("EnsureInfra".equals(taskName)) {
                log.info("📤 [AWS Step Functions] TaskStateEntered - Task: {}", taskName);
                publishStageEvent(deploymentId, 3, "ECS 배포 시작 중",
                    Map.of("stage", 3));
                return "ENSURE_INFRA_IN_PROGRESS";
            }

            // Stage 4: RegisterTaskAndDeploy (CodeDeploy Blue/Green)
            if ("RegisterTaskAndDeploy".equals(taskName)) {
                log.info("📤 [AWS Step Functions] TaskStateEntered - Task: {}", taskName);
                publishStageEvent(deploymentId, 4, "CodeDeploy Blue/Green 배포 시작",
                    Map.of("stage", 4));
                return "REGISTER_TASK_IN_PROGRESS";
            }

            // ✅ CheckDeployment는 Stage 5가 아니므로 무시 (내부 상태만 추적)
            // Stage 4까지만 사용하므로 CheckDeployment 관련 이벤트는 발행하지 않음

        } catch (Exception e) {
            log.debug("Failed to analyze TaskStateEntered", e);
        }

        return null;
    }

    /**
     * TaskStateExited 이벤트 분석
     * Task가 완료될 때 호출되는 이벤트
     * Task의 output에서 상세 정보를 추출하여 SSE 이벤트를 발행
     *
     * @param deploymentId 배포 ID
     * @param event ExecutionHistory 이벤트
     * @param awsConnection AWS 연결 정보 (URL 해석용)
     */
    private String analyzeTaskStateExited(String deploymentId, HistoryEvent event, AwsConnection awsConnection) {
        try {
            // ✅ event.toString()에서 output 추출
            String eventString = event.toString();
            log.info("📤 [TaskStateExited-EVENT-STRING] Full event: {}",
                eventString.length() > 800 ? eventString.substring(0, 800) + "..." : eventString);

            String taskOutput = null;

            // 1단계: JSON 형식의 output 문자열 추출
            // "output={...}" 또는 "output": "{\n...}" 형식에서 추출
            int outputIdx = eventString.indexOf("output=");
            if (outputIdx != -1) {
                int startIdx = outputIdx + 7; // "output=" 다음부터
                // " 또는 { 문자부터 시작
                while (startIdx < eventString.length() &&
                       eventString.charAt(startIdx) != '"' &&
                       eventString.charAt(startIdx) != '{') {
                    startIdx++;
                }

                if (startIdx < eventString.length()) {
                    // JSON 객체의 끝을 찾기
                    int braceCount = 0;
                    int endIdx = startIdx;
                    boolean inString = false;

                    for (; endIdx < eventString.length(); endIdx++) {
                        char c = eventString.charAt(endIdx);
                        if (c == '"' && (endIdx == 0 || eventString.charAt(endIdx - 1) != '\\')) {
                            inString = !inString;
                        } else if (!inString) {
                            if (c == '{') braceCount++;
                            else if (c == '}') {
                                braceCount--;
                                if (braceCount == 0) {
                                    taskOutput = eventString.substring(startIdx, endIdx + 1);
                                    log.info("📤 [TaskStateExited-Parsed] output extracted: {}",
                                        taskOutput.length() > 300 ? taskOutput.substring(0, 300) + "..." : taskOutput);
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            if (taskOutput == null || taskOutput.isEmpty()) {
                log.debug("TaskStateExited - No output found, returning null");
                return null;
            }

            Map<String, Object> outputMap = objectMapper.readValue(taskOutput, Map.class);
            // ✅ 전체 output JSON 로깅
            log.info("📤 [TaskStateExited-FULL-JSON] fullOutput: {}", objectMapper.writeValueAsString(outputMap));

            String stageStatus = (String) outputMap.get("stage");

            // Stage 3: EnsureInfra 완료
            if (stageStatus != null && stageStatus.contains("ENSURE_INFRA")) {
                log.info("📤 [AWS Step Functions] CheckDeployment output - Stage: {}, Payload: {}", stageStatus, objectMapper.writeValueAsString(outputMap));
                Map<String, Object> details = extractEnsureInfraDetails(outputMap);
                publishStageEvent(deploymentId, 3, "ECS 배포 완료", details);
                return "ENSURE_INFRA_COMPLETED";
            }

            // Stage 4: RegisterTaskAndDeploy 완료 (Blue/Green 배포 진행)
            if (stageStatus != null && stageStatus.contains("REGISTER_TASK")) {
                log.info("📤 [AWS Step Functions] RegisterTaskAndDeploy output - Stage: {}, Payload: {}", stageStatus, objectMapper.writeValueAsString(outputMap));
                Map<String, Object> details = extractBlueGreenDetails(deploymentId, outputMap, awsConnection);
                publishStageEvent(deploymentId, 4, "CodeDeploy Blue/Green 배포 진행 중", details);
                return "REGISTER_TASK_COMPLETED";
            }

            // ✅ CheckDeployment Task 감지 (3분 자동 대기)
            if (stageStatus != null && stageStatus.contains("CHECK_DEPLOYMENT")) {
                log.info("✅ [CheckDeployment-Detected] CheckDeployment Task 시작! 3분 자동 대기 후 DEPLOYMENT_READY 상태로 변경됨 - deploymentId: {}", deploymentId);
                log.info("📤 [CheckDeployment-Output] fullOutput: {}", objectMapper.writeValueAsString(outputMap));
                return "DEPLOYMENT_READY";
            }

        } catch (Exception e) {
            log.debug("Failed to analyze TaskStateExited", e);
        }

        return null;
    }

    /**
     * TaskStateEntered 이벤트에서 상태명 추출
     * Step Functions의 stateEnteredEventDetails.name 필드에서 추출
     *
     * @param event HistoryEvent
     * @return 상태명 (EnsureInfra, RegisterTaskAndDeploy, CheckDeployment 등)
     */
    private String extractStateNameFromTaskEvent(HistoryEvent event) {
        try {
            // AWS SDK의 getter 메서드로 직접 접근
            var details = event.stateEnteredEventDetails();

            if (details != null && details.name() != null) {
                return details.name();
            }

            // 폴백: 기존 방식으로도 시도 (호환성)
            String eventString = event.toString();
            return extractFieldFromEventString(eventString, "resource");

        } catch (Exception e) {
            log.debug("Failed to extract state name from task event", e);
        }

        return null;
    }

    /**
     * Event 문자열에서 특정 필드값 추출
     * 예: output="{...}", resource="EnsureInfra"
     */
    private String extractFieldFromEventString(String eventString, String fieldName) {
        try {
            String pattern = fieldName + "=";
            int idx = eventString.indexOf(pattern);
            if (idx == -1) {
                return null;
            }

            int startIdx = idx + pattern.length();
            // " 또는 ' 문자 건너뛰기
            if (startIdx < eventString.length() && (eventString.charAt(startIdx) == '"' || eventString.charAt(startIdx) == '\'')) {
                startIdx++;
            }

            // 종료 문자 찾기
            int endIdx = eventString.indexOf("\"", startIdx);
            if (endIdx == -1) {
                endIdx = eventString.indexOf("'", startIdx);
            }
            if (endIdx == -1) {
                endIdx = eventString.indexOf(",", startIdx);
            }
            if (endIdx == -1) {
                endIdx = eventString.indexOf("}", startIdx);
            }

            if (endIdx > startIdx) {
                return eventString.substring(startIdx, endIdx).trim();
            }
        } catch (Exception e) {
            log.debug("Failed to extract field: {}", fieldName, e);
        }

        return null;
    }

    /**
     * EnsureInfra Task의 output에서 세부 정보 추출
     */
    private Map<String, Object> extractEnsureInfraDetails(Map<String, Object> outputMap) {
        Map<String, Object> details = new HashMap<>();

        // output 예시:
        // {
        //   "stage": "ENSURE_INFRA_COMPLETED",
        //   "clusterName": "panda-cluster",
        //   "serviceName": "panda-service",
        //   "taskDefinition": "panda-task:1"
        // }

        if (outputMap.containsKey("clusterName")) {
            details.put("clusterName", outputMap.get("clusterName"));
        }
        if (outputMap.containsKey("serviceName")) {
            details.put("serviceName", outputMap.get("serviceName"));
        }
        if (outputMap.containsKey("taskDefinition")) {
            details.put("taskDefinition", outputMap.get("taskDefinition"));
        }

        details.put("stage", 3);
        return details;
    }

    /**
     * RegisterTaskAndDeploy Task의 output에서 Blue/Green 서비스 정보 추출
     *
     * @param deploymentId 배포 ID
     * @param outputMap Step Functions Task output
     * @param awsConnection AWS 연결 정보 (URL 해석용)
     * @return 세부 정보 맵
     */
    private Map<String, Object> extractBlueGreenDetails(String deploymentId, Map<String, Object> outputMap,
                                                        AwsConnection awsConnection) {
        Map<String, Object> details = new HashMap<>();

        // output 예시:
        // {
        //   "stage": "REGISTER_TASK_COMPLETED",
        //   "clusterName": "panda-cluster",
        //   "serviceName": "panda-service",
        //   "blueService": {
        //     "serviceArn": "arn:aws:ecs:ap-northeast-2:123456789012:service/panda-cluster/panda-blue",
        //     "url": "http://blue.example.com:8080"
        //   },
        //   "greenService": {
        //     "serviceArn": "arn:aws:ecs:ap-northeast-2:123456789012:service/panda-cluster/panda-green",
        //     "url": "http://green.example.com:8080"
        //   }
        // }

        String clusterName = null;
        String serviceName = null;
        String blueServiceArn = null;
        String greenServiceArn = null;
        String blueUrl = null;
        String greenUrl = null;

        if (outputMap.containsKey("clusterName")) {
            clusterName = (String) outputMap.get("clusterName");
            details.put("clusterName", clusterName);
        }
        if (outputMap.containsKey("serviceName")) {
            serviceName = (String) outputMap.get("serviceName");
            details.put("serviceName", serviceName);
        }

        // Blue Service 처리
        if (outputMap.containsKey("blueService")) {
            Object blueObj = outputMap.get("blueService");
            if (blueObj instanceof Map) {
                Map<String, Object> blueService = (Map<String, Object>) blueObj;
                if (blueService.containsKey("serviceArn")) {
                    blueServiceArn = (String) blueService.get("serviceArn");
                    details.put("blueServiceArn", blueServiceArn);
                }
                if (blueService.containsKey("url")) {
                    blueUrl = (String) blueService.get("url");
                    details.put("blueUrl", blueUrl);
                }
            }
        }

        // Green Service 처리
        if (outputMap.containsKey("greenService")) {
            Object greenObj = outputMap.get("greenService");
            if (greenObj instanceof Map) {
                Map<String, Object> greenService = (Map<String, Object>) greenObj;
                if (greenService.containsKey("serviceArn")) {
                    greenServiceArn = (String) greenService.get("serviceArn");
                    details.put("greenServiceArn", greenServiceArn);
                }
                if (greenService.containsKey("url")) {
                    greenUrl = (String) greenService.get("url");
                    details.put("greenUrl", greenUrl);
                }
            }
        }

        // URL이 없으면 ECS Service 정보로부터 해석
        if (blueUrl == null && blueServiceArn != null && clusterName != null && awsConnection != null) {
            try {
                log.info("Resolving Blue service URL from ARN: {}", blueServiceArn);
                blueUrl = ecsServiceUrlResolverService.resolveServiceUrl(blueServiceArn, clusterName, awsConnection);
                if (blueUrl != null) {
                    details.put("blueUrl", blueUrl);
                    log.info("Resolved Blue service URL: {}", blueUrl);
                }
            } catch (Exception e) {
                log.warn("Failed to resolve Blue service URL from ARN: {}", blueServiceArn, e);
            }
        }

        if (greenUrl == null && greenServiceArn != null && clusterName != null && awsConnection != null) {
            try {
                log.info("Resolving Green service URL from ARN: {}", greenServiceArn);
                greenUrl = ecsServiceUrlResolverService.resolveServiceUrl(greenServiceArn, clusterName, awsConnection);
                if (greenUrl != null) {
                    details.put("greenUrl", greenUrl);
                    log.info("Resolved Green service URL: {}", greenUrl);
                }
            } catch (Exception e) {
                log.warn("Failed to resolve Green service URL from ARN: {}", greenServiceArn, e);
            }
        }

        // Blue 서비스 상태 발행
        if (blueUrl != null) {
            publishStageEvent(deploymentId, 4, "Blue 서비스 실행 중", Map.of("url", blueUrl));
        }

        // Green 서비스 상태 발행
        if (greenUrl != null) {
            publishStageEvent(deploymentId, 4, "Green 서비스 준비 완료", Map.of("url", greenUrl));
        }

        details.put("stage", 4);
        return details;
    }

    /**
     * CheckDeployment Task의 output에서 HealthCheck 및 트래픽 전환 정보 추출
     */
    private Map<String, Object> extractHealthCheckDetails(Map<String, Object> outputMap) {
        Map<String, Object> details = new HashMap<>();

        // output 예시:
        // {
        //   "stage": "CHECK_DEPLOYMENT_COMPLETED",
        //   "healthCheckStatus": "SUCCESS",
        //   "healthCheckCount": 5,
        //   "activeService": "green",
        //   "blueUrl": "http://blue.example.com:8080",
        //   "greenUrl": "http://green.example.com:8080",
        //   "blueLatency": 250,
        //   "greenLatency": 180,
        //   "blueErrorRate": 0.01,
        //   "greenErrorRate": 0.005
        // }

        if (outputMap.containsKey("healthCheckStatus")) {
            details.put("healthCheckStatus", outputMap.get("healthCheckStatus"));
        }
        if (outputMap.containsKey("healthCheckCount")) {
            details.put("passedChecks", outputMap.get("healthCheckCount"));
        }
        if (outputMap.containsKey("activeService")) {
            details.put("activeService", outputMap.get("activeService"));
        }
        if (outputMap.containsKey("blueUrl")) {
            details.put("blueUrl", outputMap.get("blueUrl"));
        }
        if (outputMap.containsKey("greenUrl")) {
            details.put("greenUrl", outputMap.get("greenUrl"));
        }
        if (outputMap.containsKey("blueLatency")) {
            details.put("blueLatencyMs", outputMap.get("blueLatency"));
        }
        if (outputMap.containsKey("greenLatency")) {
            details.put("greenLatencyMs", outputMap.get("greenLatency"));
        }
        if (outputMap.containsKey("blueErrorRate")) {
            details.put("blueErrorRate", outputMap.get("blueErrorRate"));
        }
        if (outputMap.containsKey("greenErrorRate")) {
            details.put("greenErrorRate", outputMap.get("greenErrorRate"));
        }

        details.put("stage", 5);
        return details;
    }

    /**
     * Object를 HistoryEvent로 캐스팅
     */
    private HistoryEvent castToHistoryEvent(Object obj) {
        try {
            if (obj instanceof HistoryEvent) {
                return (HistoryEvent) obj;
            }
            // AWS SDK의 HistoryEvent로 변환
            String jsonString = objectMapper.writeValueAsString(obj);
            return objectMapper.readValue(jsonString, HistoryEvent.class);
        } catch (Exception e) {
            log.debug("Failed to cast to HistoryEvent", e);
            return null;
        }
    }

    /**
     * SSE 이벤트 발행 헬퍼 메서드
     */
    private void publishStageEvent(String deploymentId, Integer stage, String message) {
        publishStageEvent(deploymentId, stage, message, Map.of("stage", stage));
    }

    private void publishStageEvent(String deploymentId, Integer stage, String message, Map<String, Object> details) {
        try {
            eventPublisher.publishStageEvent(deploymentId, stage,
                String.format("[Stage %d] %s", stage, message), details);
        } catch (Exception e) {
            log.debug("Failed to publish stage event", e);
        }
    }

    /**
     * ExecutionHistory를 분석하면서 모니터링 컨텍스트 업데이트
     *
     * @param deploymentId 배포 ID
     * @param events ExecutionHistory Events
     * @param context 모니터링 컨텍스트 (blueServiceArn, greenServiceArn 등 저장)
     * @param awsConnection AWS 연결 정보
     * @return 현재 Stage
     */
    // ✅ PollingResult를 반환하도록 변경 + lastProcessedEventId로 중복 제거
    private PollingResult analyzeExecutionHistoryWithContext(String deploymentId, List<?> events,
                                                              Map<String, Object> context,
                                                              AwsConnection awsConnection,
                                                              long lastProcessedEventId) {
        if (events == null || events.isEmpty()) {
            return new PollingResult("RUNNING", lastProcessedEventId);
        }

        String currentStage = "RUNNING";
        long maxEventId = lastProcessedEventId;
        String lastTaskName = "";
        long lastTaskStartedTime = 0;

        try {
            // ✅ Event를 ID 순서로 정렬 (오래된 것부터 처리하도록)
            List<HistoryEvent> sortedEvents = events.stream()
                .map(this::castToHistoryEvent)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingLong(HistoryEvent::id))
                .toList();

            // ✅ 현재 실행 중인 Task 정보 파악 (최신 Task 추적)
            for (int i = sortedEvents.size() - 1; i >= 0; i--) {
                HistoryEvent evt = sortedEvents.get(i);
                if ("TaskStateEntered".equals(evt.typeAsString())) {
                    String taskName = extractStateNameFromTaskEvent(evt);
                    if (taskName != null) {
                        lastTaskName = taskName;
                        lastTaskStartedTime = evt.timestamp() != null ? evt.timestamp().getEpochSecond() : 0;
                        break;
                    }
                }
            }

            // ✅ 정렬된 이벤트를 순서대로 탐색
            for (HistoryEvent event : sortedEvents) {
                // ✅ 마지막 처리한 이벤트 ID보다 작거나 같으면 스킵 (중복 제거)
                if (event.id() <= lastProcessedEventId) {
                    continue;
                }

                maxEventId = event.id();  // ✅ 현재 이벤트 ID 업데이트

                String eventType = event.typeAsString();
                long eventTimestamp = event.timestamp() != null ? event.timestamp().getEpochSecond() : 0;
                log.debug("Processing Event #{}: type={}, timestamp={}", event.id(), eventType, eventTimestamp);

                // ExecutionFailed 체크
                if (event.typeAsString() != null && event.typeAsString().equals("ExecutionFailed")) {
                    log.info("📤 [AWS Step Functions] ExecutionFailed - Event ID: {}", event.id());
                    log.warn("Execution failed for deploymentId: {}", deploymentId);
                    publishStageEvent(deploymentId, 4, "배포 실패");  // ✅ Stage 4까지만 사용
                    return new PollingResult("FAILED", maxEventId);  // ✅ PollingResult 반환
                }

                // ExecutionSucceeded 체크
                if (event.typeAsString() != null && event.typeAsString().equals("ExecutionSucceeded")) {
                    log.info("📤 [AWS Step Functions] ExecutionSucceeded - Event ID: {}", event.id());
                    log.info("Execution succeeded for deploymentId: {}", deploymentId);
                    publishStageEvent(deploymentId, 4, "배포 완료", Map.of("finalService", "green"));  // ✅ Stage 4까지만 사용
                    return new PollingResult("SUCCEEDED", maxEventId);  // ✅ PollingResult 반환
                }

                // TaskScheduled 이벤트
                if (event.typeAsString() != null && event.typeAsString().equals("TaskScheduled")) {
                    log.debug("📤 [Event-Detail] TaskScheduled - eventId: {}, timestamp: {}", event.id(), eventTimestamp);
                }

                // TaskStarted 이벤트
                if (event.typeAsString() != null && event.typeAsString().equals("TaskStarted")) {
                    log.debug("📤 [Event-Detail] TaskStarted - eventId: {}, timestamp: {}", event.id(), eventTimestamp);
                }

                // TaskSucceeded 이벤트 (Task 완료 - 매우 중요)
                if (event.typeAsString() != null && event.typeAsString().equals("TaskSucceeded")) {
                    log.info("📤 [Event-Detail] TaskSucceeded - 이전 Task 완료! eventId: {}, timestamp: {} (마지막 이벤트: {})",
                        event.id(), eventTimestamp, event.id());
                }

                // TaskStateEntered 이벤트 (Task 시작)
                if (event.typeAsString() != null && event.typeAsString().equals("TaskStateEntered")) {
                    String taskName = extractStateNameFromTaskEvent(event);
                    log.info("📤 [Event-Detail] TaskStateEntered - taskName: {}, eventId: {}, timestamp: {}",
                        taskName, event.id(), eventTimestamp);
                    String stage = analyzeTaskStateEntered(deploymentId, event);
                    if (stage != null && !Objects.equals(stage, currentStage)) {
                        currentStage = stage;
                    }
                }

                // WaitState 이벤트 추적
                if (event.typeAsString() != null && event.typeAsString().equals("WaitStateEntered")) {
                    log.info("📤 [Event-Detail] WaitStateEntered - eventId: {}, timestamp: {} (⏳ 체크포인트 또는 대기 상태 - 이 후 자동 진행 예정)",
                        event.id(), eventTimestamp);
                }
                if (event.typeAsString() != null && event.typeAsString().equals("WaitStateExited")) {
                    log.info("📤 [Event-Detail] WaitStateExited - eventId: {}, timestamp: {} (대기 완료 - 다음 Step으로 진행)",
                        event.id(), eventTimestamp);
                }

                // TaskStateExited 이벤트 (Task 완료) - awsConnection 전달
                if (event.typeAsString() != null && event.typeAsString().equals("TaskStateExited")) {
                    // ✅ Event 전체 구조 로깅 (output 파악용)
                    try {
                        String fullEventString = event.toString();
                        // output 필드 있는지 확인
                        if (fullEventString.contains("output")) {
                            log.info("📤 [Event-Detail] TaskStateExited FULL - eventId: {}, event: {}",
                                event.id(),
                                fullEventString.length() > 800 ? fullEventString.substring(0, 800) + "..." : fullEventString);
                        } else {
                            log.info("📤 [Event-Detail] TaskStateExited - eventId: {}, timestamp: {}, (output field not found in event)",
                                event.id(), eventTimestamp);
                        }
                    } catch (Exception e) {
                        log.debug("Failed to log TaskStateExited details", e);
                    }

                    String stage = analyzeTaskStateExited(deploymentId, event, awsConnection);
                    if (stage != null) {
                        currentStage = stage;

                        // TaskStateExited에서 추출된 정보를 context에 저장
                        try {
                            String eventString = event.toString();
                            String taskOutput = extractFieldFromEventString(eventString, "output");

                            if (taskOutput != null && !taskOutput.isEmpty()) {
                                Map<String, Object> outputMap = objectMapper.readValue(taskOutput, Map.class);
                                String stageStatus = (String) outputMap.get("stage");

                                // Stage 4 완료 - Blue/Green 서비스 정보 저장
                                if (stageStatus != null && stageStatus.contains("REGISTER_TASK")) {
                                    log.info("📤 [AWS Step Functions] RegisterTaskAndDeploy output - Stage: {}, Payload: {}", stageStatus, objectMapper.writeValueAsString(outputMap));
                                    String greenUrl = null;
                                    if (outputMap.containsKey("blueService")) {
                                        Object blueObj = outputMap.get("blueService");
                                        if (blueObj instanceof Map) {
                                            Map<String, Object> blueService = (Map<String, Object>) blueObj;
                                            context.put("blueServiceArn", blueService.get("serviceArn"));
                                        }
                                    }
                                    if (outputMap.containsKey("greenService")) {
                                        Object greenObj = outputMap.get("greenService");
                                        if (greenObj instanceof Map) {
                                            Map<String, Object> greenService = (Map<String, Object>) greenObj;
                                            context.put("greenServiceArn", greenService.get("serviceArn"));
                                            if (greenService.containsKey("url")) {
                                                greenUrl = (String) greenService.get("url");
                                                context.put("greenUrl", greenUrl);
                                            }
                                        }
                                    }
                                    if (outputMap.containsKey("clusterName")) {
                                        context.put("clusterName", outputMap.get("clusterName"));
                                    }
                                    if (outputMap.containsKey("serviceName")) {
                                        context.put("serviceName", outputMap.get("serviceName"));
                                    }
                                    // CodeDeploy 정보 저장
                                    if (outputMap.containsKey("codeDeployDeploymentId")) {
                                        context.put("codeDeployDeploymentId", outputMap.get("codeDeployDeploymentId"));
                                    }
                                    if (outputMap.containsKey("codeDeployApplicationName")) {
                                        context.put("codeDeployApplicationName", outputMap.get("codeDeployApplicationName"));
                                    }

                                    // Health Check 실행 (Green URL이 있는 경우)
                                    if (greenUrl != null && !greenUrl.isEmpty()) {
                                        try {
                                            String codeDeployDeploymentId = (String) context.get("codeDeployDeploymentId");
                                            String codeDeployApplicationName = (String) context.get("codeDeployApplicationName");
                                            triggerHealthCheck(deploymentId, greenUrl, codeDeployDeploymentId,
                                                codeDeployApplicationName, awsConnection);
                                        } catch (Exception e) {
                                            log.warn("Failed to trigger health check for deploymentId: {}", deploymentId, e);
                                        }
                                    }
                                }

                                // ✅ CheckDeployment Task 감지 (3분 자동 대기)
                                if (stageStatus != null && stageStatus.contains("CHECK_DEPLOYMENT")) {
                                    log.info("✅ [CheckDeployment-Detected-Polling] CheckDeployment Task 감지! 3분 자동 대기 후 DEPLOYMENT_READY 상태로 변경 - deploymentId: {}", deploymentId);
                                    log.info("📤 [CheckDeployment-Output-Polling] fullOutput: {}", objectMapper.writeValueAsString(outputMap));

                                    // ✅ DEPLOYMENT_READY stage로 업데이트
                                    currentStage = "DEPLOYMENT_READY";
                                    return new PollingResult(currentStage, maxEventId);
                                }
                            }
                        } catch (Exception e) {
                            log.debug("Failed to extract monitoring context", e);
                        }

                        if (currentStage != null) {
                            return new PollingResult(currentStage, maxEventId);  // ✅ PollingResult 반환
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error analyzing execution history for deploymentId: {}", deploymentId, e);
        }

        return new PollingResult(currentStage, maxEventId);  // ✅ PollingResult 반환
    }


    /**
     * Green 서비스 Health Check 및 트래픽 전환 실행 (비동기)
     *
     * @param deploymentId 배포 ID
     * @param greenUrl Green 서비스 URL
     * @param codeDeployDeploymentId CodeDeploy 배포 ID
     * @param codeDeployApplicationName CodeDeploy 애플리케이션명
     * @param awsConnection AWS 연결 정보
     */
    private void triggerHealthCheck(String deploymentId, String greenUrl,
                                    String codeDeployDeploymentId, String codeDeployApplicationName,
                                    AwsConnection awsConnection) {
        executorService.submit(() -> {
            try {
                log.info("Triggering health check for deploymentId: {}, greenUrl: {}", deploymentId, greenUrl);

                // StageEventHelper 생성
                com.panda.backend.feature.deploy.event.StageEventHelper stageHelper =
                    new com.panda.backend.feature.deploy.event.StageEventHelper(deploymentId, eventPublisher);

                // Health Check 실행
                healthCheckService.performHealthCheckAndTrafficSwitch(
                    deploymentId,
                    stageHelper,
                    greenUrl,
                    codeDeployDeploymentId,
                    codeDeployApplicationName,
                    awsConnection
                );

                log.info("Health check completed successfully for deploymentId: {}", deploymentId);

            } catch (Exception e) {
                log.error("Health check failed for deploymentId: {}", deploymentId, e);
                try {
                    eventPublisher.publishErrorEvent(deploymentId,
                        "Health Check 실패: " + e.getMessage());
                } catch (Exception publishEx) {
                    log.warn("Failed to publish error event for health check failure", publishEx);
                }
            }
        });
    }

    /**
     * 최종 배포 결과 저장
     *
     * @param deploymentId 배포 ID
     * @param owner GitHub owner
     * @param repo GitHub repo
     * @param branch 배포 브랜치
     * @param finalStage 최종 상태 (SUCCEEDED 또는 FAILED)
     * @param monitoringContext 모니터링 컨텍스트
     * @param startTimeMs 배포 시작 시간 (밀리초)
     * @param eventCount 발행된 이벤트 개수
     */
    private void saveFinalDeploymentResult(String deploymentId, String owner, String repo, String branch,
                                           String finalStage, Map<String, Object> monitoringContext,
                                           long startTimeMs, int eventCount) {
        try {
            LocalDateTime startedAt = LocalDateTime.now().minusNanos((System.currentTimeMillis() - startTimeMs) * 1_000_000);
            LocalDateTime completedAt = LocalDateTime.now();
            long durationSeconds = (System.currentTimeMillis() - startTimeMs) / 1000;

            // 기본 정보
            DeploymentResult result = DeploymentResult.builder()
                .deploymentId(deploymentId)
                .status("SUCCEEDED".equals(finalStage) ? "COMPLETED" : "FAILED")
                .owner(owner)
                .repo(repo)
                .branch(branch)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .durationSeconds(durationSeconds)
                .eventCount(eventCount)
                .build();

            // 성공 시 추가 정보 채우기
            if ("SUCCEEDED".equals(finalStage)) {
                result.setFinalService("green");

                // 모니터링 컨텍스트에서 URL 추출
                if (monitoringContext.containsKey("blueUrl")) {
                    result.setBlueUrl((String) monitoringContext.get("blueUrl"));
                }
                if (monitoringContext.containsKey("greenUrl")) {
                    result.setGreenUrl((String) monitoringContext.get("greenUrl"));
                }

                // 성능 메트릭 추출 (있는 경우)
                if (monitoringContext.containsKey("blueLatencyMs")) {
                    Object blueLatency = monitoringContext.get("blueLatencyMs");
                    if (blueLatency instanceof Number) {
                        result.setBlueLatencyMs(((Number) blueLatency).longValue());
                    }
                }
                if (monitoringContext.containsKey("greenLatencyMs")) {
                    Object greenLatency = monitoringContext.get("greenLatencyMs");
                    if (greenLatency instanceof Number) {
                        result.setGreenLatencyMs(((Number) greenLatency).longValue());
                    }
                }
                if (monitoringContext.containsKey("blueErrorRate")) {
                    Object blueError = monitoringContext.get("blueErrorRate");
                    if (blueError instanceof Number) {
                        result.setBlueErrorRate(((Number) blueError).doubleValue());
                    }
                }
                if (monitoringContext.containsKey("greenErrorRate")) {
                    Object greenError = monitoringContext.get("greenErrorRate");
                    if (greenError instanceof Number) {
                        result.setGreenErrorRate(((Number) greenError).doubleValue());
                    }
                }
            } else {
                // 실패 시 에러 메시지 설정
                if (monitoringContext.containsKey("errorMessage")) {
                    result.setErrorMessage((String) monitoringContext.get("errorMessage"));
                } else {
                    result.setErrorMessage("배포 실패: 알 수 없는 오류");
                }

                // 실패해도 URL은 저장할 수 있음
                if (monitoringContext.containsKey("blueUrl")) {
                    result.setBlueUrl((String) monitoringContext.get("blueUrl"));
                }
                if (monitoringContext.containsKey("greenUrl")) {
                    result.setGreenUrl((String) monitoringContext.get("greenUrl"));
                }
            }

            // 결과 저장
            deploymentResultStore.save(result);
            log.info("Deployment result saved - deploymentId: {}, status: {}, duration: {}s",
                deploymentId, result.getStatus(), durationSeconds);

        } catch (Exception e) {
            log.error("Failed to save deployment result for deploymentId: {}", deploymentId, e);
        }
    }

    /**
     * 배포 준비 완료 결과 저장 (수동 전환 대기 상태)
     *
     * @param deploymentId 배포 ID
     * @param owner GitHub owner
     * @param repo GitHub repo
     * @param branch 배포 브랜치
     * @param monitoringContext 모니터링 컨텍스트 (Blue/Green URL 등)
     * @param startTimeMs 배포 시작 시간 (밀리초)
     * @param eventCount 발행된 이벤트 개수
     * @param awsConnection AWS 연결 정보
     */
    private void saveDeploymentReadyResult(String deploymentId, String owner, String repo, String branch,
                                          Map<String, Object> monitoringContext,
                                          long startTimeMs, int eventCount, AwsConnection awsConnection) {
        try {
            LocalDateTime startedAt = LocalDateTime.now().minusNanos((System.currentTimeMillis() - startTimeMs) * 1_000_000);
            LocalDateTime completedAt = LocalDateTime.now();
            long durationSeconds = (System.currentTimeMillis() - startTimeMs) / 1000;

            // 배포 준비 완료 상태로 저장
            DeploymentResult result = DeploymentResult.builder()
                .deploymentId(deploymentId)
                .status("DEPLOYMENT_READY")  // ✅ 수동 전환 대기 상태
                .owner(owner)
                .repo(repo)
                .branch(branch)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .durationSeconds(durationSeconds)
                .eventCount(eventCount)
                .build();

            // Blue/Green URL 저장
            if (monitoringContext.containsKey("blueUrl")) {
                result.setBlueUrl((String) monitoringContext.get("blueUrl"));
            }
            if (monitoringContext.containsKey("greenUrl")) {
                result.setGreenUrl((String) monitoringContext.get("greenUrl"));
            }

            // Blue/Green Service ARN 저장
            if (monitoringContext.containsKey("blueServiceArn")) {
                result.setBlueServiceArn((String) monitoringContext.get("blueServiceArn"));
            }
            if (monitoringContext.containsKey("greenServiceArn")) {
                result.setGreenServiceArn((String) monitoringContext.get("greenServiceArn"));
            }

            // AWS 연결 정보 저장 (Lambda 호출 시 필요)
            if (awsConnection != null) {
                result.setAwsAccessKeyId(awsConnection.getAccessKeyId());
                result.setAwsSecretAccessKey(awsConnection.getSecretAccessKey());
                result.setAwsSessionToken(awsConnection.getSessionToken());
            }

            deploymentResultStore.save(result);
            log.info("Deployment ready result saved - deploymentId: {}, status: DEPLOYMENT_READY, duration: {}s",
                deploymentId, durationSeconds);

        } catch (Exception e) {
            log.error("Failed to save deployment ready result for deploymentId: {}", deploymentId, e);
        }
    }

    /**
     * 타임아웃 결과 저장
     *
     * @param deploymentId 배포 ID
     * @param owner GitHub owner
     * @param repo GitHub repo
     * @param branch 배포 브랜치
     * @param startTimeMs 배포 시작 시간 (밀리초)
     * @param eventCount 발행된 이벤트 개수
     * @param errorMessage 타임아웃 에러 메시지
     */
    private void saveTimeoutResult(String deploymentId, String owner, String repo, String branch,
                                   long startTimeMs, int eventCount, String errorMessage) {
        try {
            LocalDateTime startedAt = LocalDateTime.now().minusNanos((System.currentTimeMillis() - startTimeMs) * 1_000_000);
            LocalDateTime completedAt = LocalDateTime.now();
            long durationSeconds = (System.currentTimeMillis() - startTimeMs) / 1000;

            DeploymentResult result = DeploymentResult.builder()
                .deploymentId(deploymentId)
                .status("FAILED")
                .owner(owner)
                .repo(repo)
                .branch(branch)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .durationSeconds(durationSeconds)
                .errorMessage(errorMessage)
                .eventCount(eventCount)
                .build();

            deploymentResultStore.save(result);
            log.info("Timeout result saved - deploymentId: {}, duration: {}s, message: {}",
                deploymentId, durationSeconds, errorMessage);

        } catch (Exception e) {
            log.error("Failed to save timeout result for deploymentId: {}", deploymentId, e);
        }
    }

}
