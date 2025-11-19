package com.panda.backend.feature.deploy.infrastructure;

import com.panda.backend.feature.deploy.event.DeploymentEventPublisher;
import com.panda.backend.feature.deploy.exception.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class DeploymentErrorHandler {

    private final DeploymentEventPublisher eventPublisher;

    // 예외 처리 및 이벤트 발행
    public void handleException(String deploymentId, Exception exception) {
        try {
            if (exception instanceof DeploymentTimeoutException) {
                handleTimeoutException(deploymentId, (DeploymentTimeoutException) exception);
            } else if (exception instanceof DockerBuildException) {
                handleDockerBuildException(deploymentId, (DockerBuildException) exception);
            } else if (exception instanceof EcsDeploymentException) {
                handleEcsDeploymentException(deploymentId, (EcsDeploymentException) exception);
            } else if (exception instanceof HealthCheckException) {
                handleHealthCheckException(deploymentId, (HealthCheckException) exception);
            } else if (exception instanceof DeploymentException) {
                handleGenericDeploymentException(deploymentId, (DeploymentException) exception);
            } else {
                handleUnexpectedException(deploymentId, exception);
            }
        } catch (Exception e) {
            log.error("Error while handling deployment exception", e);
            // 최후의 수단: 일반 에러 메시지 발행
            eventPublisher.publishErrorEvent(deploymentId, "Internal error occurred: " + e.getMessage());
        }
    }

    // 타임아웃 예외 처리
    private void handleTimeoutException(String deploymentId, DeploymentTimeoutException e) {
        log.error("Deployment timeout - deploymentId: {}, stage: {}, duration: {}s, timeout: {}s",
                deploymentId, e.getStage(), e.getDurationSeconds(), e.getTimeoutSeconds());

        String errorMessage = String.format(
                "Deployment timed out at Stage %d after %d seconds (timeout: %d seconds)",
                e.getStage(), e.getDurationSeconds(), e.getTimeoutSeconds()
        );

        eventPublisher.publishErrorEvent(deploymentId, errorMessage);

        // 타임아웃 로그 상세 기록
        Map<String, Object> details = Map.of(
                "errorCode", "DEPLOYMENT_TIMEOUT",
                "stage", e.getStage(),
                "durationSeconds", e.getDurationSeconds(),
                "timeoutSeconds", e.getTimeoutSeconds()
        );
        logErrorDetails(deploymentId, "DEPLOYMENT_TIMEOUT", details);
    }

    // Docker 빌드 실패 처리
    private void handleDockerBuildException(String deploymentId, DockerBuildException e) {
        log.error("Docker build failed - deploymentId: {}, image: {}, exitCode: {}",
                deploymentId, e.getImageName(), e.getExitCode());

        String errorMessage = String.format(
                "Docker build failed: %s (Exit code: %d)",
                e.getMessage(), e.getExitCode() != null ? e.getExitCode() : -1
        );

        eventPublisher.publishErrorEvent(deploymentId, errorMessage);

        Map<String, Object> details = Map.of(
                "errorCode", "DOCKER_BUILD_FAILED",
                "stage", 1,
                "imageName", e.getImageName() != null ? e.getImageName() : "unknown",
                "exitCode", e.getExitCode() != null ? e.getExitCode() : -1
        );
        logErrorDetails(deploymentId, "DOCKER_BUILD_FAILED", details);
    }

    // ECS 배포 실패 처리
    private void handleEcsDeploymentException(String deploymentId, EcsDeploymentException e) {
        log.error("ECS deployment failed - deploymentId: {}, cluster: {}, service: {}",
                deploymentId, e.getClusterName(), e.getServiceName());

        String errorMessage = String.format(
                "ECS deployment failed: %s (Cluster: %s, Service: %s)",
                e.getMessage(),
                e.getClusterName() != null ? e.getClusterName() : "unknown",
                e.getServiceName() != null ? e.getServiceName() : "unknown"
        );

        eventPublisher.publishErrorEvent(deploymentId, errorMessage);

        Map<String, Object> details = Map.of(
                "errorCode", "ECS_DEPLOYMENT_FAILED",
                "stage", 3,
                "clusterName", e.getClusterName() != null ? e.getClusterName() : "unknown",
                "serviceName", e.getServiceName() != null ? e.getServiceName() : "unknown"
        );
        logErrorDetails(deploymentId, "ECS_DEPLOYMENT_FAILED", details);
    }

    // 헬스체크 실패 처리
    private void handleHealthCheckException(String deploymentId, HealthCheckException e) {
        log.error("Health check failed - deploymentId: {}, service: {}, failed: {}/{}",
                deploymentId, e.getServiceUrl(), e.getFailedCheckCount(), e.getTotalCheckCount());

        String errorMessage = String.format(
                "Health check failed: %s (Failed: %d/%d checks)",
                e.getMessage(),
                e.getFailedCheckCount() != null ? e.getFailedCheckCount() : 0,
                e.getTotalCheckCount() != null ? e.getTotalCheckCount() : 0
        );

        eventPublisher.publishErrorEvent(deploymentId, errorMessage);

        Map<String, Object> details = Map.of(
                "errorCode", "HEALTH_CHECK_FAILED",
                "stage", 5,
                "serviceUrl", e.getServiceUrl() != null ? e.getServiceUrl() : "unknown",
                "failedChecks", e.getFailedCheckCount() != null ? e.getFailedCheckCount() : 0,
                "totalChecks", e.getTotalCheckCount() != null ? e.getTotalCheckCount() : 0
        );
        logErrorDetails(deploymentId, "HEALTH_CHECK_FAILED", details);
    }

    // 일반 배포 예외 처리
    private void handleGenericDeploymentException(String deploymentId, DeploymentException e) {
        log.error("Deployment failed - deploymentId: {}, stage: {}, errorCode: {}",
                deploymentId, e.getStage(), e.getErrorCode(), e);

        String errorMessage = String.format(
                "Deployment failed at Stage %d: %s",
                e.getStage() != null ? e.getStage() : 0,
                e.getMessage()
        );

        eventPublisher.publishErrorEvent(deploymentId, errorMessage);

        Map<String, Object> details = Map.of(
                "errorCode", e.getErrorCode() != null ? e.getErrorCode() : "UNKNOWN",
                "stage", e.getStage() != null ? e.getStage() : 0,
                "message", e.getMessage()
        );
        logErrorDetails(deploymentId, e.getErrorCode(), details);
    }

    // 예상 외 예외 처리
    private void handleUnexpectedException(String deploymentId, Exception e) {
        log.error("Unexpected exception during deployment - deploymentId: {}", deploymentId, e);

        String errorMessage = String.format(
                "Unexpected error: %s (%s)",
                e.getMessage(),
                e.getClass().getSimpleName()
        );

        eventPublisher.publishErrorEvent(deploymentId, errorMessage);

        Map<String, Object> details = Map.of(
                "errorCode", "UNEXPECTED_ERROR",
                "exceptionClass", e.getClass().getSimpleName(),
                "message", e.getMessage()
        );
        logErrorDetails(deploymentId, "UNEXPECTED_ERROR", details);
    }

    // 에러 상세 정보 로깅
    private void logErrorDetails(String deploymentId, String errorCode, Map<String, Object> details) {
        try {
            log.error("Error details - deploymentId: {}, errorCode: {}, details: {}",
                    deploymentId, errorCode, details);
        } catch (Exception e) {
            log.warn("Failed to log error details", e);
        }
    }

    // 에러 메시지 생성
    public String createErrorMessage(String deploymentId, Integer stage, String reason) {
        return String.format(
                "Deployment failed at Stage %d: %s (DeploymentId: %s)",
                stage != null ? stage : 0,
                reason,
                deploymentId
        );
    }

    // 에러 메시지에서 에러 코드 추출
    public String extractErrorCode(Exception exception) {
        if (exception instanceof DeploymentException) {
            DeploymentException de = (DeploymentException) exception;
            return de.getErrorCode() != null ? de.getErrorCode() : "UNKNOWN";
        }
        return "UNEXPECTED_ERROR";
    }
}
