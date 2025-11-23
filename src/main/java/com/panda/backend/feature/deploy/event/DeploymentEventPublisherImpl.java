package com.panda.backend.feature.deploy.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeploymentEventPublisherImpl implements DeploymentEventPublisher {

    private final DeploymentEventStore deploymentEventStore;

    @Override
    public void publishStageEvent(String deploymentId, Integer stage, String message) {
        publishStageEvent(deploymentId, stage, message, null);
    }

    @Override
    public void publishStageEvent(String deploymentId, Integer stage, String message, Map<String, Object> details) {
        try {
            // ✅ Stage 이벤트 전에 connected 이벤트 먼저 전송
            deploymentEventStore.sendConnectedEvent(deploymentId);

            // 이벤트 생성 및 발행
            DeploymentEvent event = new DeploymentEvent();
            event.setType("stage");
            event.setMessage(message);

            // 통일된 형식: stage, timestamp는 항상 포함, 추가 details는 merge
            Map<String, Object> unifiedDetails = new java.util.HashMap<>();
            unifiedDetails.put("stage", stage);
            unifiedDetails.put("timestamp", java.time.Instant.now().toString());
            if (details != null) {
                unifiedDetails.putAll(details);
            }
            event.setDetails(unifiedDetails);

            // 발행
            deploymentEventStore.broadcastEvent(deploymentId, event);

            log.info("Stage event published - deploymentId: {}, stage: {}, message: {}",
                     deploymentId, stage, message);
        } catch (Exception e) {
            log.error("Failed to publish stage event for deployment: {}", deploymentId, e);
        }
    }

    @Override
    public void publishSuccessEvent(String deploymentId, String finalService, String blueUrl, String greenUrl) {
        try {
            // 성공 이벤트 발행
            DeploymentEvent event = new DeploymentEvent();
            event.setType("success");
            event.setMessage("Deployment completed successfully");
            event.setDetails(Map.of(
                    "finalService", finalService,
                    "blueUrl", blueUrl,
                    "greenUrl", greenUrl
            ));

            deploymentEventStore.sendDoneEvent(deploymentId, "Deployment completed successfully");

            log.info("Success event published - deploymentId: {}, finalService: {}",
                     deploymentId, finalService);
        } catch (Exception e) {
            log.error("Failed to publish success event for deployment: {}", deploymentId, e);
        }
    }

    @Override
    public void publishErrorEvent(String deploymentId, String errorMessage) {
        publishErrorEvent(deploymentId, errorMessage, null);
    }

    /**
     * 에러 이벤트 발행 (상세정보 포함)
     *
     * @param deploymentId 배포 ID
     * @param errorMessage 에러 메시지
     * @param errorDetails 에러 상세정보 (선택사항)
     */
    public void publishErrorEvent(String deploymentId, String errorMessage, Map<String, Object> errorDetails) {
        try {
            // 에러 이벤트 발행
            deploymentEventStore.sendErrorEvent(deploymentId, errorMessage, errorDetails);

            log.warn("Error event published - deploymentId: {}, error: {}",
                     deploymentId, errorMessage);
        } catch (Exception e) {
            log.error("Failed to publish error event for deployment: {}", deploymentId, e);
        }
    }

    @Override
    public void initializeDeployment(String deploymentId, String owner, String repo, String branch, String awsRegion) {
        log.info("Deployment initialized - deploymentId: {}, owner: {}, repo: {}",
                 deploymentId, owner, repo);
    }

    @Override
    public void publishStepFunctionsProgress(String deploymentId, String stepFunctionsStage) {
        try {
            // ✅ Step Functions 진행상황 이벤트 전에 connected 이벤트 먼저 전송
            deploymentEventStore.sendConnectedEvent(deploymentId);

            String message = mapStepFunctionsStageToMessage(stepFunctionsStage);
            Integer stageNumber = mapStepFunctionsStageToNumber(stepFunctionsStage);

            DeploymentEvent event = new DeploymentEvent();
            event.setType("stage");  // ✅ stage 타입으로 변경
            event.setMessage(message);

            // 통일된 형식: stage, timestamp는 항상 포함, stepFunctionsStage는 추가 정보
            Map<String, Object> unifiedDetails = new java.util.HashMap<>();
            unifiedDetails.put("stage", stageNumber);
            unifiedDetails.put("timestamp", java.time.Instant.now().toString());
            unifiedDetails.put("stepFunctionsStage", stepFunctionsStage);
            event.setDetails(unifiedDetails);

            deploymentEventStore.broadcastEvent(deploymentId, event);

            log.info("Step Functions progress published - deploymentId: {}, stage: {}, message: {}",
                deploymentId, stepFunctionsStage, message);

        } catch (Exception e) {
            log.error("Failed to publish Step Functions progress for deployment: {}", deploymentId, e);
        }
    }

    /**
     * Step Functions Stage를 사용자 친화적 메시지로 변환
     */
    private String mapStepFunctionsStageToMessage(String stage) {
        return switch (stage) {
            case "ENSURE_INFRA_IN_PROGRESS" -> "Checking and provisioning infrastructure...";
            case "ENSURE_INFRA_COMPLETED" -> "Infrastructure check and provisioning completed.";
            case "REGISTER_TASK_IN_PROGRESS" -> "Updating Task Definition and starting deployment...";
            case "REGISTER_TASK_COMPLETED" -> "Task Definition update completed.";
            case "CHECK_DEPLOYMENT_IN_PROGRESS" -> "Blue/Green deployment in progress...";
            case "DEPLOYMENT_READY" -> "Green environment is ready. Waiting for traffic switch approval.";
            case "SUCCEEDED" -> "Deployment succeeded! Green environment is now active.";
            case "FAILED" -> "Deployment failed.";
            case "RUNNING" -> "Deployment running...";
            default -> stage;
        };
    }

    /**
     * Step Functions Stage를 Stage Number로 변환
     */
    private Integer mapStepFunctionsStageToNumber(String stage) {
        return switch (stage) {
            case "ENSURE_INFRA_IN_PROGRESS" -> 3;
            case "ENSURE_INFRA_COMPLETED" -> 3;
            case "REGISTER_TASK_IN_PROGRESS" -> 4;
            case "REGISTER_TASK_COMPLETED" -> 4;
            case "CHECK_DEPLOYMENT_IN_PROGRESS" -> 4;
            case "DEPLOYMENT_READY" -> 4;
            case "SUCCEEDED" -> 4;
            case "FAILED" -> 4;
            default -> 4;
        };
    }

}
