package com.panda.backend.feature.deploy.infrastructure;

import com.panda.backend.feature.connect.entity.AwsConnection;
import com.panda.backend.feature.connect.entity.GitHubConnection;
import com.panda.backend.feature.deploy.application.DeploymentPipelineService;
import com.panda.backend.feature.deploy.event.DeploymentEventPublisher;
import com.panda.backend.feature.deploy.event.DeploymentEventStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class DeploymentTask implements Runnable {

    private final String deploymentId;
    private final GitHubConnection ghConnection;
    private final AwsConnection awsConnection;
    private final String owner;
    private final String repo;
    private final String branch;
    private final DeploymentPipelineService deploymentPipelineService;
    private final DeploymentEventPublisher eventPublisher;
    private final DeploymentEventStore eventStore;

    @Override
    public void run() {
        try {
            log.info("Starting deployment task for deploymentId: {}, repo: {}/{}", deploymentId, owner, repo);

            // 배포 파이프라인 실행
            deploymentPipelineService.triggerDeploymentPipeline(
                    deploymentId,
                    ghConnection,
                    awsConnection,
                    owner,
                    repo,
                    branch
            );

            log.info("Deployment task completed successfully for deploymentId: {}", deploymentId);

        } catch (Exception e) {
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                log.warn("Deployment task interrupted for deploymentId: {}", deploymentId, e);
                eventPublisher.publishErrorEvent(deploymentId, "Deployment was interrupted");
                Thread.currentThread().interrupt();
            } else {
                log.error("Deployment task failed for deploymentId: {}", deploymentId, e);
                eventPublisher.publishErrorEvent(deploymentId, "Deployment failed: " + e.getMessage());
            }
        }
    }
}
