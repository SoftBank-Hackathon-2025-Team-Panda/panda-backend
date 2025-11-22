package com.panda.backend.feature.deploy.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panda.backend.feature.deploy.event.DeploymentEventPublisher;
import com.panda.backend.feature.deploy.infrastructure.ExecutionArnStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.*;

import java.util.*;
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
    private final ObjectMapper objectMapper;

    @Value("${aws.step-functions.polling-interval-ms:2000}")
    private long pollingIntervalMs;

    @Value("${aws.step-functions.max-polling-duration-ms:1800000}")
    private long maxPollingDurationMs;

    @Value("${aws.step-functions.wait-for-execution-arn-ms:10000}")
    private long waitForExecutionArnMs;

    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    /**
     * 비동기로 Step Functions 폴링 시작
     * ECR 푸시 완료 직후 호출되어야 함
     *
     * @param deploymentId 배포 ID
     */
    public void startPollingAsync(String deploymentId) {
        executorService.submit(() -> {
            try {
                pollExecutionHistory(deploymentId);
            } catch (Exception e) {
                log.error("Polling failed for deploymentId: {}", deploymentId, e);
                eventPublisher.publishErrorEvent(deploymentId,
                    "Step Functions 모니터링 오류: " + e.getMessage());
            }
        });

        log.info("Step Functions polling started asynchronously for deploymentId: {}", deploymentId);
    }

    /**
     * ExecutionHistory를 폴링하여 상태 변화 감지
     *
     * @param deploymentId 배포 ID
     */
    private void pollExecutionHistory(String deploymentId) {
        long pollingStartTime = System.currentTimeMillis();
        String executionArn = null;
        String previousStage = null;
        int pollCount = 0;

        try {
            // Step 1: Secrets Manager에서 ExecutionArn 조회
            log.debug("Waiting {}ms for ExecutionArn to be saved in Secrets Manager...", waitForExecutionArnMs);
            Thread.sleep(waitForExecutionArnMs);

            executionArn = executionArnStore.get(deploymentId);

            if (executionArn == null) {
                String errorMsg = "ExecutionArn not found in Secrets Manager after waiting";
                log.error(errorMsg);
                eventPublisher.publishErrorEvent(deploymentId, errorMsg);
                return;
            }

            log.info("ExecutionArn retrieved: {}, starting polling...", executionArn);

            // Step 2: ExecutionHistory 폴링 (최대 30분)
            while (System.currentTimeMillis() - pollingStartTime < maxPollingDurationMs) {
                pollCount++;

                try {
                    // GetExecutionHistory API 호출
                    GetExecutionHistoryResponse history = sfnClient.getExecutionHistory(
                        GetExecutionHistoryRequest.builder()
                            .executionArn(executionArn)
                            .build()
                    );

                    // 현재 stage 분석
                    String currentStage = analyzeExecutionHistory(deploymentId, history.events());

                    log.debug("Poll #{} - deploymentId: {}, stage: {}", pollCount, deploymentId, currentStage);

                    // 상태 변화 감지
                    if (!Objects.equals(currentStage, previousStage)) {
                        log.info("Stage changed: {} → {}", previousStage, currentStage);
                        eventPublisher.publishStepFunctionsProgress(deploymentId, currentStage);
                        previousStage = currentStage;
                    }

                    // 완료/실패 시 폴링 종료
                    if ("SUCCEEDED".equals(currentStage) || "FAILED".equals(currentStage)) {
                        log.info("Polling completed for deploymentId: {}, final stage: {}", deploymentId, currentStage);
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
            eventPublisher.publishErrorEvent(deploymentId,
                "Step Functions 모니터링 중 오류 발생: " + e.getMessage());
        } finally {
            // 배포 완료 후 Secrets Manager에서 정리
            if (executionArn != null) {
                try {
                    executionArnStore.remove(deploymentId);
                } catch (Exception e) {
                    log.warn("Failed to clean up ExecutionArn for deploymentId: {}", deploymentId, e);
                }
            }
        }
    }

    /**
     * ExecutionHistory Events를 분석하여 현재 Stage 파악
     *
     * Step Functions의 State 이름:
     * - EnsureInfra: 인프라 점검 및 생성
     * - RegisterTaskAndDeploy: Task Definition 재정의 및 CodeDeploy 시작
     * - CheckDeployment: 배포 상태 확인
     * - DeploymentStatusRouter: 상태 분기
     * - WaitBeforeRecheck: 대기
     * - DeploymentSucceeded: 성공
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
                String eventString = eventObj.toString();

                log.debug("Event #{}: {}", i, eventString.substring(0, Math.min(100, eventString.length())));

                // ExecutionFailed 체크
                if (eventString.contains("ExecutionFailed")) {
                    log.warn("Execution failed for deploymentId: {}", deploymentId);
                    return "FAILED";
                }

                // ExecutionSucceeded 체크
                if (eventString.contains("ExecutionSucceeded")) {
                    log.info("Execution succeeded for deploymentId: {}", deploymentId);
                    return "SUCCEEDED";
                }

                // TaskStateEntered 이벤트 (Task 시작)
                if (eventString.contains("TaskStateEntered")) {
                    String stage = extractStageFromEventString(eventString);
                    if (stage != null) {
                        return stage;
                    }
                }

                // TaskStateExited 이벤트 (Task 완료)
                if (eventString.contains("TaskStateExited")) {
                    try {
                        String output = extractTaskOutputFromEventString(eventString);
                        if (output != null && !output.isEmpty()) {
                            String stage = extractStageFromTaskOutput(output);
                            if (stage != null) {
                                log.debug("Task completed - stage: {}", stage);
                                return stage;
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Failed to parse task output", e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Error analyzing execution history for deploymentId: {}", deploymentId, e);
        }

        return "RUNNING";
    }

    /**
     * Event 문자열에서 State 이름 추출
     */
    private String extractStageFromEventString(String eventString) {
        if (eventString.contains("EnsureInfra")) {
            return "ENSURE_INFRA_IN_PROGRESS";
        } else if (eventString.contains("RegisterTaskAndDeploy")) {
            return "REGISTER_TASK_IN_PROGRESS";
        } else if (eventString.contains("CheckDeployment")) {
            return "CHECK_DEPLOYMENT_IN_PROGRESS";
        } else if (eventString.contains("DeploymentFailed")) {
            return "FAILED";
        } else if (eventString.contains("DeploymentSucceeded")) {
            return "SUCCEEDED";
        }
        return null;
    }

    /**
     * Event 문자열에서 Task Output 추출
     */
    private String extractTaskOutputFromEventString(String eventString) {
        // output= 뒤의 내용 추출
        int outputIdx = eventString.indexOf("output=");
        if (outputIdx != -1) {
            int startIdx = outputIdx + 7;
            int endIdx = eventString.indexOf(",", startIdx);
            if (endIdx == -1) {
                endIdx = eventString.indexOf("}", startIdx);
            }
            if (endIdx != -1 && endIdx > startIdx) {
                return eventString.substring(startIdx, endIdx).trim();
            }
        }
        return null;
    }

    /**
     * Lambda의 Task Output에서 stage 정보 추출
     * Output 예시:
     * {
     *   "status": "SUCCESS",
     *   "stage": "ENSURE_INFRA_COMPLETED",
     *   "event": {...}
     * }
     *
     * @param taskOutput Task의 output JSON 문자열
     * @return stage 값 또는 null
     */
    private String extractStageFromTaskOutput(String taskOutput) {
        try {
            Map<String, Object> outputMap = objectMapper.readValue(taskOutput, Map.class);

            // stage 필드 확인
            if (outputMap.containsKey("stage")) {
                String stage = (String) outputMap.get("stage");
                if ("ENSURE_INFRA_COMPLETED".equals(stage)) {
                    return "ENSURE_INFRA_COMPLETED";
                } else if (stage.contains("COMPLETED") || stage.contains("UPDATED")) {
                    return stage;
                }
            }

            // Payload 하위에서 stage 확인
            if (outputMap.containsKey("Payload")) {
                Map<String, Object> payload = (Map<String, Object>) outputMap.get("Payload");
                if (payload != null && payload.containsKey("stage")) {
                    return (String) payload.get("stage");
                }
            }

        } catch (Exception e) {
            log.debug("Failed to extract stage from task output", e);
        }

        return null;
    }
}
