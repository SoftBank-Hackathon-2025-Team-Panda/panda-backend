package com.panda.backend.feature.deploy.exception;

public class DeploymentException extends RuntimeException {

    private String deploymentId;
    private Integer stage;
    private String errorCode;

    public DeploymentException(String message) {
        super(message);
    }

    public DeploymentException(String message, Throwable cause) {
        super(message, cause);
    }

    public DeploymentException(String message, String deploymentId, Integer stage) {
        super(message);
        this.deploymentId = deploymentId;
        this.stage = stage;
    }

    public DeploymentException(String message, String deploymentId, Integer stage, String errorCode) {
        super(message);
        this.deploymentId = deploymentId;
        this.stage = stage;
        this.errorCode = errorCode;
    }

    public String getDeploymentId() {
        return deploymentId;
    }

    public Integer getStage() {
        return stage;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
