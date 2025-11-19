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
            // Stage 업데이트
            deploymentEventStore.updateStage(deploymentId, stage);

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
            // 메타데이터 업데이트
            deploymentEventStore.completeDeployment(deploymentId, finalService, blueUrl, greenUrl);

            // 성공 이벤트 발행
            DeploymentEvent event = new DeploymentEvent();
            event.setType("done");
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
        try {
            // 메타데이터 업데이트
            deploymentEventStore.failDeployment(deploymentId, errorMessage);

            // 에러 이벤트 발행
            deploymentEventStore.sendErrorEvent(deploymentId, errorMessage);

            log.warn("Error event published - deploymentId: {}, error: {}",
                     deploymentId, errorMessage);
        } catch (Exception e) {
            log.error("Failed to publish error event for deployment: {}", deploymentId, e);
        }
    }

    @Override
    public void initializeDeployment(String deploymentId, String owner, String repo, String branch, String awsRegion) {
        try {
            deploymentEventStore.initializeMetadata(deploymentId, owner, repo, branch, awsRegion);
            log.info("Deployment initialized - deploymentId: {}, owner: {}, repo: {}",
                     deploymentId, owner, repo);
        } catch (Exception e) {
            log.error("Failed to initialize deployment: {}", deploymentId, e);
        }
    }

}
