package com.panda.backend.feature.deploy.application;

import com.panda.backend.feature.connect.entity.AwsConnection;
import com.panda.backend.feature.connect.entity.GitHubConnection;
import com.panda.backend.feature.deploy.dto.DeployRequest;
import com.panda.backend.feature.deploy.dto.DeployResponse;
import com.panda.backend.feature.deploy.event.DeploymentEventPublisher;
import com.panda.backend.feature.deploy.event.DeploymentEventStore;
import com.panda.backend.feature.deploy.infrastructure.DeploymentTask;
import com.panda.backend.feature.deploy.infrastructure.DeploymentTaskExecutor;
import com.panda.backend.feature.connect.infrastructure.ConnectionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StartDeploymentService {

    private final ConnectionStore connectionStore;
    private final DeploymentPipelineService deploymentPipelineService;
    private final DeploymentEventStore deploymentEventStore;
    private final DeploymentEventPublisher eventPublisher;
    private final DeploymentTaskExecutor deploymentTaskExecutor;
    private final EventBridgeRuleService eventBridgeRuleService;

    public DeployResponse start(DeployRequest request) {
        // GitHub 연결 확인
        GitHubConnection ghConnection = connectionStore.getGitHubConnection(request.getGithubConnectionId())
                .orElseThrow(() -> new IllegalArgumentException("GitHub connection not found"));

        // AWS 연결 확인
        AwsConnection awsConnection = connectionStore.getAwsConnection(request.getAwsConnectionId())
                .orElseThrow(() -> new IllegalArgumentException("AWS connection not found"));

        // 배포 ID 생성
        String deploymentId = "dep_" + UUID.randomUUID().toString().substring(0, 10);

        // 배포 메타데이터 초기화
        eventPublisher.initializeDeployment(
                deploymentId,
                request.getOwner(),
                request.getRepo(),
                request.getBranch(),
                awsConnection.getRegion()
        );

        // EventBridge Rule 생성
        try {
            eventBridgeRuleService.createEventBridgeRule(
                    awsConnection.getRegion(),
                    request.getOwner(),
                    request.getRepo(),
                    awsConnection.getAccessKeyId(),
                    awsConnection.getSecretAccessKey(),
                    awsConnection.getSessionToken()
            );
            log.info("EventBridge rule created for deployment: {}", deploymentId);
        } catch (Exception e) {
            log.error("Failed to create EventBridge rule for deployment {}", deploymentId, e);
            deploymentEventStore.failDeployment(deploymentId, "Failed to create EventBridge rule: " + e.getMessage());
            throw new RuntimeException("Failed to create EventBridge rule: " + e.getMessage(), e);
        }

        // 배포 작업 생성
        DeploymentTask deploymentTask = new DeploymentTask(
                deploymentId,
                ghConnection,
                awsConnection,
                request.getOwner(),
                request.getRepo(),
                request.getBranch(),
                deploymentPipelineService,
                eventPublisher,
                deploymentEventStore
        );

        // 작업 실행
        try {
            deploymentTaskExecutor.executeDeployment(deploymentId, deploymentTask);
            log.info("Deployment {} started successfully", deploymentId);
        } catch (Exception e) {
            log.error("Failed to start deployment {}", deploymentId, e);
            deploymentEventStore.failDeployment(deploymentId, "Failed to start deployment: " + e.getMessage());
            throw new RuntimeException("Failed to start deployment: " + e.getMessage(), e);
        }

        return new DeployResponse(deploymentId, "Deployment started. Listen to /api/v1/deploy/{id}/events");
    }
}
