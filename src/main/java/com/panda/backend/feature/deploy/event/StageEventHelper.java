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
        STAGE_DESCRIPTIONS.put(5, "HealthCheck 및 트래픽 전환");
        STAGE_DESCRIPTIONS.put(6, "배포 완료");
    }

    /**
     * Stage 1: Dockerfile 탐색 및 Docker Build
     */
    public void stage1Start() {
        updateStage(1, "Repository 클론 중...");
    }

    public void stage1RepositoryCloned(String clonePath) {
        publishProgress("Repository 클론 완료", Map.of("path", clonePath));
    }

    public void stage1DockerfileSearching() {
        publishProgress("Dockerfile 검색 중...");
    }

    public void stage1DockerfileFound(String dockerfilePath) {
        publishProgress("Dockerfile 찾음", Map.of("path", dockerfilePath));
    }

    public void stage1BuildStarting() {
        publishProgress("Docker 이미지 빌드 시작...");
    }

    public void stage1BuildProgress(String message) {
        publishProgress("Docker 빌드 진행 중: " + message);
    }

    public void stage1BuildCompleted(String imageName) {
        publishProgress("Docker 이미지 빌드 완료", Map.of(
                "imageName", imageName
        ));
    }

    /**
     * Stage 2: ECR Push
     */
    public void stage2Start() {
        updateStage(2, "ECR로 이미지 Push 중...");
    }

    public void stage2RepositoryEnsured(String repositoryName) {
        publishProgress("ECR 리포지토리 확인 완료", Map.of("repository", repositoryName));
    }

    public void stage2LoginStarting() {
        publishProgress("ECR 로그인 중...");
    }

    public void stage2LoginCompleted() {
        publishProgress("ECR 로그인 완료");
    }

    public void stage2PushStarting(String ecrImageUri) {
        publishProgress("이미지 Push 시작", Map.of("uri", ecrImageUri));
    }

    public void stage2PushProgress(String message) {
        publishProgress("Push 진행 중: " + message);
    }

    public void stage2PushCompleted(String ecrImageUri) {
        publishProgress("이미지 Push 완료", Map.of("uri", ecrImageUri));
    }

    /**
     * Stage 3: ECS 배포 시작
     */
    public void stage3Start(String ecrImageUri) {
        updateStage(3, "ECS 배포 시작");
        publishProgress("ECS 서비스 생성/업데이트 중", Map.of("image", ecrImageUri));
    }

    public void stage3ServiceCreated(String serviceName, String clusterName) {
        publishProgress("ECS 서비스 생성 완료", Map.of(
                "serviceName", serviceName,
                "clusterName", clusterName
        ));
    }

    public void stage3ServiceUpdated(String serviceName) {
        publishProgress("ECS 서비스 업데이트 완료", Map.of("serviceName", serviceName));
    }

    /**
     * Stage 4: CodeDeploy Blue/Green Lifecycle
     */
    public void stage4Start(String ecrImageUri) {
        updateStage(4, "CodeDeploy Blue/Green 배포 시작");
        publishProgress("Blue/Green 배포 초기화 중", Map.of("image", ecrImageUri));
    }

    public void stage4BlueServiceRunning(String blueUrl) {
        publishProgress("Blue 서비스 실행 중", Map.of("url", blueUrl));
    }

    public void stage4GreenServiceSpinning(String greenUrl) {
        publishProgress("Green 서비스 시작 중", Map.of("url", greenUrl));
    }

    public void stage4GreenServiceReady(String greenUrl) {
        publishProgress("Green 서비스 준비 완료", Map.of("url", greenUrl));
    }

    public void stage4LifecycleHook(String hookName) {
        publishProgress("CodeDeploy Lifecycle Hook: " + hookName);
    }

    /**
     * Stage 5: HealthCheck 및 트래픽 전환
     */
    public void stage5Start(String greenUrl) {
        updateStage(5, "Green 서비스 HealthCheck 및 트래픽 전환");
        publishProgress("HealthCheck 시작", Map.of("url", greenUrl));
    }

    public void stage5HealthCheckRunning(String greenUrl) {
        publishProgress("Green 서비스 HealthCheck 진행 중", Map.of("url", greenUrl));
    }

    public void stage5HealthCheckPassed(String greenUrl, int passedChecks) {
        publishProgress("HealthCheck 성공", Map.of(
                "url", greenUrl,
                "passedChecks", passedChecks
        ));
    }

    public void stage5HealthCheckFailed(String greenUrl, String reason) {
        publishProgress("HealthCheck 실패: " + reason, Map.of("url", greenUrl));
    }

    public void stage5TrafficSwitching(String fromService, String toService) {
        publishProgress("트래픽 전환 중", Map.of(
                "from", fromService,
                "to", toService
        ));
    }

    public void stage5TrafficSwitched(String toService) {
        publishProgress("트래픽 전환 완료", Map.of("activeService", toService));
    }

    /**
     * Stage 6: 배포 완료
     */
    public void stage6Complete(String finalService, String blueUrl, String greenUrl) {
        publishProgress("배포 완료", Map.of(
                "finalService", finalService,
                "blueUrl", blueUrl,
                "greenUrl", greenUrl
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
