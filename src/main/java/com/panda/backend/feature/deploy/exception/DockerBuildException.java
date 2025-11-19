package com.panda.backend.feature.deploy.exception;

public class DockerBuildException extends DeploymentException {

    private String imageName;
    private Integer exitCode;

    public DockerBuildException(String message, String deploymentId) {
        super(message, deploymentId, 1, "DOCKER_BUILD_FAILED");
    }

    public DockerBuildException(String message, String deploymentId, String imageName, Integer exitCode) {
        super(message, deploymentId, 1, "DOCKER_BUILD_FAILED");
        this.imageName = imageName;
        this.exitCode = exitCode;
    }

    public String getImageName() {
        return imageName;
    }

    public Integer getExitCode() {
        return exitCode;
    }
}
