package com.panda.backend.global.exception;

import com.panda.backend.feature.deploy.exception.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 글로벌 예외 처리 핸들러
 *
 * 모든 예외를 API_SPECIFICATION.md의 에러 형식에 맞게 처리
 * - timestamp: 발생 시간
 * - status: HTTP 상태 코드
 * - error: 에러 타입
 * - message: 상세 메시지
 * - deploymentId: 배포 ID (있는 경우)
 * - stage: 배포 단계 (있는 경우)
 * - errorCode: 에러 코드 (있는 경우)
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 배포 타임아웃 예외 처리 (408 Request Timeout)
     */
    @ExceptionHandler(DeploymentTimeoutException.class)
    public ResponseEntity<?> handleDeploymentTimeoutException(DeploymentTimeoutException e, WebRequest request) {
        log.error("Deployment timeout occurred - deploymentId: {}, stage: {}, duration: {}s, timeout: {}s",
            e.getDeploymentId(), e.getStage(), e.getDurationSeconds(), e.getTimeoutSeconds(), e);

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("status", HttpStatus.REQUEST_TIMEOUT.value());
        errorResponse.put("error", "Deployment Timeout");
        errorResponse.put("message", e.getMessage());
        errorResponse.put("deploymentId", e.getDeploymentId());
        errorResponse.put("stage", e.getStage());
        errorResponse.put("errorCode", ErrorCode.DEPLOYMENT_TIMEOUT.getCode());
        errorResponse.put("durationSeconds", e.getDurationSeconds());
        errorResponse.put("timeoutSeconds", e.getTimeoutSeconds());

        return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT).body(errorResponse);
    }

    /**
     * Docker 빌드 실패 예외 처리 (400 Bad Request)
     */
    @ExceptionHandler(DockerBuildException.class)
    public ResponseEntity<?> handleDockerBuildException(DockerBuildException e, WebRequest request) {
        log.error("Docker build failed - deploymentId: {}, imageName: {}, exitCode: {}",
            e.getDeploymentId(), e.getImageName(), e.getExitCode(), e);

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("status", HttpStatus.BAD_REQUEST.value());
        errorResponse.put("error", "Deployment Error");
        errorResponse.put("message", e.getMessage());
        errorResponse.put("deploymentId", e.getDeploymentId());
        errorResponse.put("stage", e.getStage());
        errorResponse.put("errorCode", ErrorCode.DOCKER_BUILD_FAILED.getCode());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * ECS 배포 실패 예외 처리 (400 Bad Request)
     */
    @ExceptionHandler(EcsDeploymentException.class)
    public ResponseEntity<?> handleEcsDeploymentException(EcsDeploymentException e, WebRequest request) {
        log.error("ECS deployment failed - deploymentId: {}, clusterName: {}, serviceName: {}",
            e.getDeploymentId(), e.getClusterName(), e.getServiceName(), e);

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("status", HttpStatus.BAD_REQUEST.value());
        errorResponse.put("error", "Deployment Error");
        errorResponse.put("message", e.getMessage());
        errorResponse.put("deploymentId", e.getDeploymentId());
        errorResponse.put("stage", e.getStage());
        errorResponse.put("errorCode", ErrorCode.ECS_DEPLOYMENT_FAILED.getCode());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * 헬스체크 실패 예외 처리 (400 Bad Request)
     */
    @ExceptionHandler(HealthCheckException.class)
    public ResponseEntity<?> handleHealthCheckException(HealthCheckException e, WebRequest request) {
        log.error("Health check failed - deploymentId: {}, serviceUrl: {}, failedChecks: {}/{}",
            e.getDeploymentId(), e.getServiceUrl(), e.getFailedCheckCount(), e.getTotalCheckCount(), e);

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("status", HttpStatus.BAD_REQUEST.value());
        errorResponse.put("error", "Deployment Error");
        errorResponse.put("message", e.getMessage());
        errorResponse.put("deploymentId", e.getDeploymentId());
        errorResponse.put("stage", e.getStage());
        errorResponse.put("errorCode", ErrorCode.HEALTH_CHECK_FAILED.getCode());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * 일반 배포 예외 처리 (400 Bad Request)
     */
    @ExceptionHandler(DeploymentException.class)
    public ResponseEntity<?> handleDeploymentException(DeploymentException e, WebRequest request) {
        log.error("Deployment error occurred - deploymentId: {}, stage: {}, errorCode: {}",
            e.getDeploymentId(), e.getStage(), e.getErrorCode(), e);

        // ErrorCode로부터 HTTP 상태 결정
        ErrorCode errorCode = ErrorCode.fromCode(e.getErrorCode());
        HttpStatus httpStatus = errorCode.getHttpStatus();

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("status", httpStatus.value());
        errorResponse.put("error", "Deployment Error");
        errorResponse.put("message", e.getMessage());
        errorResponse.put("deploymentId", e.getDeploymentId());
        errorResponse.put("stage", e.getStage());
        errorResponse.put("errorCode", e.getErrorCode());

        return ResponseEntity.status(httpStatus).body(errorResponse);
    }

    /**
     * IllegalArgumentException 처리 (400 Bad Request)
     * 결과 조회 시 배포 ID가 없을 때 등
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgumentException(IllegalArgumentException e, WebRequest request) {
        log.error("Illegal argument error: {}", e.getMessage(), e);

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("status", HttpStatus.BAD_REQUEST.value());
        errorResponse.put("error", "Deployment Error");
        errorResponse.put("message", e.getMessage());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    /**
     * 예상하지 못한 모든 예외 처리 (500 Internal Server Error)
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGlobalException(Exception e, WebRequest request) {
        log.error("Unexpected error occurred - errorClass: {}", e.getClass().getSimpleName(), e);

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        errorResponse.put("error", "Internal Server Error");
        errorResponse.put("message", e.getMessage() != null ? e.getMessage() : "Unexpected error");
        errorResponse.put("errorCode", ErrorCode.UNEXPECTED_ERROR.getCode());
        errorResponse.put("exceptionClass", e.getClass().getSimpleName());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }
}
