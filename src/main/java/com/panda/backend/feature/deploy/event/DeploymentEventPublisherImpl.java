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
            event.setDetails(details != null ? details : Map.of("stage", stage));

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
            event.setDetails(Map.of(
                "stage", stageNumber,
                "stepFunctionsStage", stepFunctionsStage,
                "timestamp", java.time.LocalDateTime.now().toString()
            ));

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
            case "ENSURE_INFRA_IN_PROGRESS" -> "⏳ 인프라 점검 및 생성 진행 중...";
            case "ENSURE_INFRA_COMPLETED" -> "✅ 인프라 점검 및 생성 완료";
            case "REGISTER_TASK_IN_PROGRESS" -> "⏳ Task Definition 업데이트 및 배포 시작 중...";
            case "REGISTER_TASK_COMPLETED" -> "✅ Task Definition 업데이트 완료";
            case "CHECK_DEPLOYMENT_IN_PROGRESS" -> "⏳ Blue/Green 배포 진행 중...";
            case "SUCCEEDED" -> "✅ 배포 성공! Green 서버 활성화됨";
            case "FAILED" -> "❌ 배포 실패";
            case "RUNNING" -> "⏳ 배포 진행 중...";
            default -> "⏳ " + stage;
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
            case "SUCCEEDED" -> 4;
            case "FAILED" -> 4;
            default -> 3;
        };
    }

}
