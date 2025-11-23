package com.panda.backend.feature.deploy.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class StageEventHelper {

    private final String deploymentId;
    private final DeploymentEventPublisher eventPublisher;

    @Getter
    private Integer currentStage = 0;

    private static final Map<Integer, String> STAGE_DESCRIPTIONS = new HashMap<>();

    static {
        STAGE_DESCRIPTIONS.put(1, "Dockerfile 탐색 및 Docker Build");
        STAGE_DESCRIPTIONS.put(2, "ECR에 이미지 Push");
        STAGE_DESCRIPTIONS.put(3, "ECS 배포 시작");
        STAGE_DESCRIPTIONS.put(4, "CodeDeploy Blue/Green Lifecycle");
    }

    /**
     * Stage 1: Dockerfile 탐색 및 Docker Build
     */
    public void stage1Start() {
        updateStage(1, "Cloning repository...");
    }

    public void stage1RepositoryCloned(String clonePath) {
        publishProgress("Repository cloned successfully", Map.of("path", clonePath));
    }

    public void stage1DockerfileSearching() {
        publishProgress("Searching for Dockerfile...");
    }

    public void stage1DockerfileFound(String dockerfilePath) {
        publishProgress("Dockerfile found", Map.of("path", dockerfilePath));
    }

    public void stage1BuildStarting() {
        publishProgress("Starting Docker image build...");
    }

    public void stage1BuildProgress(String message) {
        publishProgress("Docker build in progress: " + message);
    }

    public void stage1BuildCompleted(String imageName) {
        publishProgress("Docker image build completed", Map.of(
                "imageName", imageName
        ));
    }

    /**
     * Stage 2: ECR Push
     */
    public void stage2Start() {
        updateStage(2, "Pushing image to ECR...");
    }

    public void stage2RepositoryEnsured(String repositoryName) {
        publishProgress("ECR repository verification completed", Map.of("repository", repositoryName));
    }

    public void stage2LoginStarting() {
        publishProgress("Logging in to ECR...");
    }

    public void stage2LoginCompleted() {
        publishProgress("ECR login completed");
    }

    public void stage2PushStarting(String ecrImageUri) {
        publishProgress("Starting image push", Map.of("uri", ecrImageUri));
    }

    public void stage2PushProgress(String message) {
        publishProgress("Push in progress: " + message);
    }

    public void stage2PushCompleted(String ecrImageUri) {
        publishProgress("Image push completed", Map.of("uri", ecrImageUri));
    }

    /**
     * Stage 3: ECS 배포 시작
     */
    public void stage3Start(String ecrImageUri) {
        updateStage(3, "Checking and provisioning infrastructure...");
        publishProgress("Creating/updating ECS service...", Map.of("image", ecrImageUri));
    }

    public void stage3ServiceCreated(String serviceName, String clusterName) {
        publishProgress("Infrastructure check and provisioning completed.", Map.of(
                "serviceName", serviceName,
                "clusterName", clusterName
        ));
    }

    public void stage3ServiceUpdated(String serviceName) {
        publishProgress("Infrastructure check and provisioning completed.", Map.of("serviceName", serviceName));
    }

    /**
     * Stage 4: CodeDeploy Blue/Green Lifecycle
     */
    public void stage4Start(String ecrImageUri) {
        updateStage(4, "Updating Task Definition and starting deployment...");
        publishProgress("Initializing Blue/Green deployment...", Map.of("image", ecrImageUri));
    }

    public void stage4BlueServiceRunning(String blueUrl) {
        publishProgress("Blue service running", Map.of("url", blueUrl));
    }

    public void stage4GreenServiceSpinning(String greenUrl) {
        publishProgress("Green service starting...", Map.of("url", greenUrl));
    }

    public void stage4GreenServiceReady(String greenUrl) {
        publishProgress("Green service ready", Map.of("url", greenUrl));
    }

    public void stage4LifecycleHook(String hookName) {
        publishProgress("CodeDeploy Lifecycle Hook: " + hookName);
    }

    public void stage4HealthCheckRunning(String greenUrl) {
        publishProgress("Blue/Green deployment in progress...", Map.of("url", greenUrl));
    }

    public void stage4HealthCheckPassed(String greenUrl, int passedChecks) {
        publishProgress("Health check passed", Map.of(
                "url", greenUrl,
                "passedChecks", passedChecks
        ));
    }

    public void stage4TrafficSwitching(String fromService, String toService) {
        publishProgress("Switching traffic...", Map.of(
                "from", fromService,
                "to", toService
        ));
    }

    public void stage4TrafficSwitched(String toService) {
        publishProgress("Traffic switched successfully", Map.of("activeService", toService));
    }

    public void stage4HealthCheckFailed(String greenUrl, String reason) {
        publishProgress("Health check failed: " + reason, Map.of("url", greenUrl));
    }

    public void stage4DeploymentReady(String blueServiceArn, String greenServiceArn, String blueUrl, String greenUrl) {
        publishProgress("Green environment is being prepared. This may take a few minutes.", Map.of(
                "blueServiceArn", blueServiceArn,
                "greenServiceArn", greenServiceArn,
                "blueUrl", blueUrl,
                "greenUrl", greenUrl,
                "message", "Call POST /api/v1/deploy/{deploymentId}/switch to proceed with traffic switch"
        ));
    }

    /**
     * 에러 이벤트 발행
     */
    public void error(String errorMessage) {
        log.error("Deployment error at stage {}: {}", currentStage, errorMessage);
        // errorMessage는 eventPublisher.publishErrorEvent()에서 처리
    }

    /**
     * Stage 업데이트 (새 Stage로 전환)
     */
    private void updateStage(Integer stage, String message) {
        currentStage = stage;
        String stageDesc = STAGE_DESCRIPTIONS.getOrDefault(stage, "Unknown Stage");
        String fullMessage = String.format("[Stage %d] %s - %s", stage, stageDesc, message);
        eventPublisher.publishStageEvent(deploymentId, stage, fullMessage);
        log.info("Stage updated - deploymentId: {}, stage: {}, message: {}", deploymentId, stage, fullMessage);
    }

    /**
     * 현재 Stage 내에서 진행 상황 업데이트
     */
    private void publishProgress(String message) {
        publishProgress(message, null);
    }

    private void publishProgress(String message, Map<String, Object> details) {
        String fullMessage = String.format("[Stage %d] %s", currentStage, message);

        if (details != null) {
            eventPublisher.publishStageEvent(deploymentId, currentStage, fullMessage, details);
        } else {
            eventPublisher.publishStageEvent(deploymentId, currentStage, fullMessage);
        }

        log.debug("Stage progress - deploymentId: {}, stage: {}, message: {}", deploymentId, currentStage, fullMessage);
    }

    /**
     * Stage 설명 반환
     */
    public static String getStageDescription(Integer stage) {
        return STAGE_DESCRIPTIONS.getOrDefault(stage, "Unknown Stage");
    }
}
