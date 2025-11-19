package com.panda.backend.feature.deploy.application;

import com.panda.backend.feature.deploy.dto.DeploymentResult;
import com.panda.backend.feature.deploy.dto.DeploymentMetadata;
import com.panda.backend.feature.deploy.event.DeploymentEventStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class GetDeploymentResultService {
    private final DeploymentEventStore deploymentEventStore;

    public DeploymentResult getResult(String deploymentId) {
        DeploymentMetadata metadata = deploymentEventStore.getMetadata(deploymentId);

        if (metadata == null) {
            throw new IllegalArgumentException("Deployment result not found: " + deploymentId);
        }

        DeploymentResult result = DeploymentResult.builder()
                .deploymentId(metadata.getDeploymentId())
                .status(metadata.getStatus())
                .owner(metadata.getOwner())
                .repo(metadata.getRepo())
                .branch(metadata.getBranch())
                .finalService(metadata.getFinalService())
                .blueUrl(metadata.getBlueUrl())
                .greenUrl(metadata.getGreenUrl())
                .startedAt(metadata.getStartedAt())
                .completedAt(metadata.getCompletedAt())
                .durationSeconds(metadata.getDurationInSeconds())
                .errorMessage(metadata.getErrorMessage())
                .blueLatencyMs(metadata.getBlueLatencyMs())
                .greenLatencyMs(metadata.getGreenLatencyMs())
                .blueErrorRate(metadata.getBlueErrorRate())
                .greenErrorRate(metadata.getGreenErrorRate())
                .eventCount(deploymentEventStore.getEventHistorySize(deploymentId))
                .build();

        return result;
    }
}
