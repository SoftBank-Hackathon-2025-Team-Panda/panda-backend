package com.panda.backend.global.exception;

import com.panda.backend.feature.deploy.exception.DeploymentException;
import com.panda.backend.feature.deploy.exception.DeploymentTimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DeploymentTimeoutException.class)
    public ResponseEntity<?> handleDeploymentTimeoutException(DeploymentTimeoutException e, WebRequest request) {
        log.error("Deployment timeout occurred", e);

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("status", HttpStatus.REQUEST_TIMEOUT.value());
        errorResponse.put("error", "Deployment Timeout");
        errorResponse.put("message", e.getMessage());
        errorResponse.put("deploymentId", e.getDeploymentId());
        errorResponse.put("stage", e.getStage());
        errorResponse.put("errorCode", e.getErrorCode());
        errorResponse.put("durationSeconds", e.getDurationSeconds());
        errorResponse.put("timeoutSeconds", e.getTimeoutSeconds());

        return ResponseEntity.status(HttpStatus.REQUEST_TIMEOUT).body(errorResponse);
    }

    @ExceptionHandler(DeploymentException.class)
    public ResponseEntity<?> handleDeploymentException(DeploymentException e, WebRequest request) {
        log.error("Deployment error occurred", e);

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("status", HttpStatus.BAD_REQUEST.value());
        errorResponse.put("error", "Deployment Error");
        errorResponse.put("message", e.getMessage());
        errorResponse.put("deploymentId", e.getDeploymentId());
        errorResponse.put("stage", e.getStage());
        errorResponse.put("errorCode", e.getErrorCode());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorResponse);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGlobalException(Exception e, WebRequest request) {
        log.error("Unexpected error occurred", e);

        Map<String, Object> errorResponse = new HashMap<>();
        errorResponse.put("timestamp", LocalDateTime.now());
        errorResponse.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        errorResponse.put("error", "Internal Server Error");
        errorResponse.put("message", e.getMessage());
        errorResponse.put("exceptionClass", e.getClass().getSimpleName());

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
    }
}
