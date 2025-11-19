package com.panda.backend.feature.deploy.event;

import java.util.Map;

public interface DeploymentEventPublisher {

    void publishStageEvent(String deploymentId, Integer stage, String message);

    void publishStageEvent(String deploymentId, Integer stage, String message, Map<String, Object> details);

    void publishSuccessEvent(String deploymentId, String finalService, String blueUrl, String greenUrl);

    void publishErrorEvent(String deploymentId, String errorMessage);

    void initializeDeployment(String deploymentId, String owner, String repo, String branch, String awsRegion);
}
