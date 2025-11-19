package com.panda.backend.feature.deploy.exception;

public class EcsDeploymentException extends DeploymentException {

    private String clusterName;
    private String serviceName;

    public EcsDeploymentException(String message, String deploymentId) {
        super(message, deploymentId, 3, "ECS_DEPLOYMENT_FAILED");
    }

    public EcsDeploymentException(String message, String deploymentId, String clusterName, String serviceName) {
        super(message, deploymentId, 3, "ECS_DEPLOYMENT_FAILED");
        this.clusterName = clusterName;
        this.serviceName = serviceName;
    }

    public String getClusterName() {
        return clusterName;
    }

    public String getServiceName() {
        return serviceName;
    }
}
