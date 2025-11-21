package com.panda.backend.feature.deploy.event;

import java.util.Map;

public interface DeploymentEventPublisher {

    void publishStageEvent(String deploymentId, Integer stage, String message);

    void publishStageEvent(String deploymentId, Integer stage, String message, Map<String, Object> details);

    void publishSuccessEvent(String deploymentId, String finalService, String blueUrl, String greenUrl);

    void publishErrorEvent(String deploymentId, String errorMessage);

    void initializeDeployment(String deploymentId, String owner, String repo, String branch, String awsRegion);

    /**
     * Step Functions 진행 상황 발행
     * 파이프라인팀의 자동화 프로세스 상태를 프론트엔드에 전달
     *
     * @param deploymentId 배포 ID
     * @param stepFunctionsStage Step Functions의 현재 stage
     *                          (예: ENSURE_INFRA_COMPLETED, REGISTER_TASK_IN_PROGRESS, SUCCEEDED, FAILED)
     */
    void publishStepFunctionsProgress(String deploymentId, String stepFunctionsStage);
}
