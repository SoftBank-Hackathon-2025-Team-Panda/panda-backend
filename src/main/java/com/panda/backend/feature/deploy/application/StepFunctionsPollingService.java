package com.panda.backend.feature.deploy.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panda.backend.feature.connect.entity.AwsConnection;
import com.panda.backend.feature.deploy.dto.DeploymentResult;
import com.panda.backend.feature.deploy.event.DeploymentEventPublisher;
import com.panda.backend.feature.deploy.event.DeploymentEventStore;
import com.panda.backend.feature.deploy.infrastructure.DeploymentResultStore;
import com.panda.backend.feature.deploy.infrastructure.ExecutionArnStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sfn.SfnClient;
import software.amazon.awssdk.services.sfn.model.GetExecutionHistoryRequest;
import software.amazon.awssdk.services.sfn.model.GetExecutionHistoryResponse;
import software.amazon.awssdk.services.sfn.model.HistoryEvent;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class StepFunctionsPollingService {

    private final SfnClient sfnClient;
    private final ExecutionArnStore executionArnStore;
    private final DeploymentEventStore eventStore;
    private final DeploymentEventPublisher publisher;
    private final DeploymentResultStore resultStore;
    private final ObjectMapper objectMapper;

    private final ExecutorService executor = Executors.newFixedThreadPool(5);

    @Value("${aws.step-functions.polling-interval-ms:2000}")
    private long interval;

    @Value("${aws.step-functions.max-polling-duration-ms:1800000}")
    private long timeout;

    @Value("${aws.step-functions.wait-for-execution-arn-ms:10000}")
    private long waitExecutionArnMs;

    /**
     * ============================
     *  PUBLIC ENTRY
     * ============================
     */
    public void startPollingAsync(String deploymentId, String owner, String repo, AwsConnection aws) {
        executor.submit(() -> {
            poll(deploymentId, owner, repo, aws);
        });
    }

    /**
     * ============================
     *  POLLING
     * ============================
     */
    private void poll(String deploymentId, String owner, String repo, AwsConnection aws) {

        log.info("▶ Step Functions Polling 시작: deploymentId={}", deploymentId);

        // 1) ExecutionArn 찾기 (Secrets Manager)
        sleep(waitExecutionArnMs);
        String executionArn = executionArnStore.get(owner, repo);

        if (executionArn == null) {
            publisher.publishErrorEvent(deploymentId, "ExecutionArn not found");
            return;
        }

        long startTime = System.currentTimeMillis();
        long lastEventId = 0L;

        Map<String, Object> ctx = new HashMap<>();
        String currentStage = "RUNNING";
        int eventCount = 0;

        while (true) {

            if (System.currentTimeMillis() - startTime > timeout) {
                saveTimeoutResult(deploymentId, owner, repo, "Timeout exceeded");
                break;
            }

            try {
                GetExecutionHistoryResponse res = sfnClient.getExecutionHistory(
                    GetExecutionHistoryRequest.builder()
                        .executionArn(executionArn)
                        .includeExecutionData(true)
                        .build()
                );

                List<HistoryEvent> events = res.events();
                if (events == null || events.isEmpty()) {
                    sleep(interval);
                    continue;
                }

                // 최신 eventId 찾기
                long maxEventId = events.stream().mapToLong(HistoryEvent::id).max().orElse(lastEventId);
                if (maxEventId == lastEventId) {
                    // no new event
                    sleep(interval);
                    continue;
                }

                // 새 이벤트만 처리
                for (HistoryEvent e : events) {
                    if (e.id() <= lastEventId) continue;

                    String type = e.typeAsString();

                    // --- 1) Execution Failed ---
                    if ("ExecutionFailed".equals(type)) {
                        currentStage = "FAILED";
                        saveFinalResult(deploymentId, owner, repo, "FAILED", ctx, startTime, eventCount);
                        return;
                    }

                    // --- 2) Execution Succeeded ---
                    if ("ExecutionSucceeded".equals(type)) {
                        currentStage = "SUCCEEDED";
                        saveFinalResult(deploymentId, owner, repo, "SUCCEEDED", ctx, startTime, eventCount);
                        return;
                    }

                    // --- 3) TaskStateEntered ---
                    if ("TaskStateEntered".equals(type)) {
                        String taskName = e.stateEnteredEventDetails().name();
                        log.info("▶ TaskStateEntered: {}", taskName);

                        switch (taskName) {
                            case "EnsureInfra" -> {
                                currentStage = "ENSURE_INFRA_IN_PROGRESS";
                                publisher.publishStageEvent(deploymentId, 3, "EnsureInfra 시작");
                            }
                            case "RegisterTaskAndDeploy" -> {
                                currentStage = "REGISTER_TASK_IN_PROGRESS";
                                publisher.publishStageEvent(deploymentId, 4, "RegisterTask 시작");
                            }
                            case "CheckDeployment" -> {
                                currentStage = "CHECK_DEPLOYMENT";
                                publisher.publishStageEvent(deploymentId, 5, "CheckDeployment 시작");
                            }
                            case "RunMetrics" -> {
                                currentStage = "RUN_METRICS";
                                publisher.publishStageEvent(deploymentId, 5, "RunMetrics 시작");
                            }
                        }
                    }

                    // --- 4) TaskStateExited (실제 JSON 파싱 지점) ---
                    if ("TaskStateExited".equals(type)) {
                        String taskName = e.stateExitedEventDetails().name();
                        String json = e.stateExitedEventDetails().output();

                        if (json == null || json.isEmpty()) continue;

                        Map<String, Object> map = objectMapper.readValue(json, Map.class);

                        switch (taskName) {
                            case "EnsureInfra" -> handleEnsureInfra(map, ctx, deploymentId);
                            case "RegisterTaskAndDeploy" -> handleRegisterTask(map, ctx, deploymentId, aws);
                            case "CheckDeployment" -> handleCheckDeployment(map, ctx, deploymentId);
                            case "RunMetrics" -> handleRunMetrics(map, ctx, deploymentId);
                        }
                    }

                    eventCount++;
                }

                lastEventId = maxEventId;
                sleep(interval);

            } catch (Exception ex) {
                log.error("Polling error", ex);
                sleep(interval);
            }
        }
    }

    /**
     * ============================
     *  HANDLERS
     * ============================
     */

    private void handleEnsureInfra(Map<String, Object> map, Map<String, Object> ctx, String deploymentId) {
        ctx.put("clusterName", map.get("clusterName"));
        ctx.put("serviceName", map.get("serviceName"));
        publisher.publishStageEvent(deploymentId, 3, "EnsureInfra 완료");
    }

    private void handleRegisterTask(Map<String, Object> map, Map<String, Object> ctx,
        String deploymentId, AwsConnection aws) {

        Map<String, Object> blue = (Map<String, Object>) map.get("blueService");
        Map<String, Object> green = (Map<String, Object>) map.get("greenService");

        if (blue != null && blue.get("url") != null) ctx.put("blueUrl", blue.get("url"));
        if (green != null && green.get("url") != null) ctx.put("greenUrl", green.get("url"));

        // CodeDeploy DeploymentId
        Map<String, Object> deployResult = (Map<String, Object>) map.get("deployResult");
        if (deployResult != null) {
            Map<String, Object> payload = (Map<String, Object>) deployResult.get("Payload");
            if (payload != null && payload.get("deploymentId") != null) {
                ctx.put("codeDeployDeploymentId", payload.get("deploymentId"));
            }
        }

        publisher.publishStageEvent(deploymentId, 4, "RegisterTask 완료");
    }

    private void handleCheckDeployment(Map<String, Object> map, Map<String, Object> ctx, String deploymentId) {
        Map<String, Object> checkResult = (Map<String, Object>) map.get("checkResult");
        if (checkResult != null) {
            Map<String, Object> payload = (Map<String, Object>) checkResult.get("Payload");
            if (payload != null) {
                Map<String, Object> inner = (Map<String, Object>) payload.get("checkResult");
                if (inner != null) {
                    ctx.put("codeDeployDeploymentId", inner.get("deploymentId"));
                    ctx.put("blueTargetGroupArn", inner.get("blueTargetGroupArn"));
                    ctx.put("greenTargetGroupArn", inner.get("greenTargetGroupArn"));
                }
            }
        }

        publisher.publishStageEvent(deploymentId, 5, "CheckDeployment 완료");
    }

    private void handleRunMetrics(Map<String, Object> map, Map<String, Object> ctx, String deploymentId) {

        Map<String, Object> metricsResult = (Map<String, Object>) map.get("metricsResult");
        if (metricsResult == null) return;

        Map<String, Object> payload = (Map<String, Object>) metricsResult.get("Payload");
        if (payload == null) return;

        Map<String, Object> blue = (Map<String, Object>) payload.get("blue");
        Map<String, Object> green = (Map<String, Object>) payload.get("green");

        if (blue != null) {
            ctx.put("blueUrl", blue.get("url"));
            ctx.put("blueLatencyMs", ((Number) blue.get("latencyMs")).longValue());
            ctx.put("blueErrorRate", ((Number) blue.get("errorRate")).doubleValue());
        }

        if (green != null) {
            ctx.put("greenUrl", green.get("url"));
            ctx.put("greenLatencyMs", ((Number) green.get("latencyMs")).longValue());
            ctx.put("greenErrorRate", ((Number) green.get("errorRate")).doubleValue());
        }

        publisher.publishStageEvent(deploymentId, 6, "Metrics 완료");
    }


    /**
     * ============================
     *  SAVE RESULTS
     * ============================
     */

    private void saveFinalResult(
        String deploymentId, String owner, String repo,
        String status, Map<String, Object> ctx,
        long startTime, int eventCount
    ) {

        DeploymentResult result = DeploymentResult.builder()
            .deploymentId(deploymentId)
            .status(status)
            .owner(owner)
            .repo(repo)
            .branch("main")
            .startedAt(LocalDateTime.now().minusNanos((System.currentTimeMillis() - startTime) * 1_000_000))
            .completedAt(LocalDateTime.now())
            .durationSeconds((System.currentTimeMillis() - startTime) / 1000)
            .eventCount(eventCount)
            .blueUrl((String) ctx.get("blueUrl"))
            .greenUrl((String) ctx.get("greenUrl"))
            .blueLatencyMs(asLong(ctx.get("blueLatencyMs")))
            .greenLatencyMs(asLong(ctx.get("greenLatencyMs")))
            .blueErrorRate(asDouble(ctx.get("blueErrorRate")))
            .greenErrorRate(asDouble(ctx.get("greenErrorRate")))
            .codeDeployDeploymentId((String) ctx.get("codeDeployDeploymentId"))
            .build();

        resultStore.save(result);
        log.info("✨ DeploymentResult 저장됨: {}", result);
    }

    private void saveTimeoutResult(String deploymentId, String owner, String repo, String message) {
        DeploymentResult result = DeploymentResult.builder()
            .deploymentId(deploymentId)
            .status("FAILED")
            .owner(owner)
            .repo(repo)
            .branch("main")
            .completedAt(LocalDateTime.now())
            .errorMessage(message)
            .build();

        resultStore.save(result);
        log.info("⛔ Timeout 저장됨");
    }

    private long asLong(Object o) {
        return o instanceof Number ? ((Number) o).longValue() : 0;
    }

    private double asDouble(Object o) {
        return o instanceof Number ? ((Number) o).doubleValue() : 0.0;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (Exception ignored) {}
    }
}
