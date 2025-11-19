package com.panda.backend.feature.deploy.exception;


public class HealthCheckException extends DeploymentException {

    private String serviceUrl;
    private Integer failedCheckCount;
    private Integer totalCheckCount;

    public HealthCheckException(String message, String deploymentId) {
        super(message, deploymentId, 5, "HEALTH_CHECK_FAILED");
    }

    public HealthCheckException(String message, String deploymentId, String serviceUrl,
                                Integer failedCheckCount, Integer totalCheckCount) {
        super(message, deploymentId, 5, "HEALTH_CHECK_FAILED");
        this.serviceUrl = serviceUrl;
        this.failedCheckCount = failedCheckCount;
        this.totalCheckCount = totalCheckCount;
    }

    public String getServiceUrl() {
        return serviceUrl;
    }

    public Integer getFailedCheckCount() {
        return failedCheckCount;
    }

    public Integer getTotalCheckCount() {
        return totalCheckCount;
    }
}
