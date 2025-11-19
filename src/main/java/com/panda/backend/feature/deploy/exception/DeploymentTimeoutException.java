package com.panda.backend.feature.deploy.exception;

public class DeploymentTimeoutException extends DeploymentException {

    private Long durationSeconds;
    private Long timeoutSeconds;

    public DeploymentTimeoutException(String message, String deploymentId, Integer stage) {
        super(message, deploymentId, stage, "DEPLOYMENT_TIMEOUT");
    }

    public DeploymentTimeoutException(String message, String deploymentId, Integer stage,
                                      Long durationSeconds, Long timeoutSeconds) {
        super(message, deploymentId, stage, "DEPLOYMENT_TIMEOUT");
        this.durationSeconds = durationSeconds;
        this.timeoutSeconds = timeoutSeconds;
    }

    public Long getDurationSeconds() {
        return durationSeconds;
    }

    public Long getTimeoutSeconds() {
        return timeoutSeconds;
    }
}
