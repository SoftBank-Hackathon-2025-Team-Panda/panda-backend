package com.panda.backend.feature.deploy.application;

import com.panda.backend.feature.connect.entity.AwsConnection;
import com.panda.backend.feature.deploy.event.StageEventHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.*;

@Slf4j
@Service
public class BlueGreenDeploymentService {

    /**
     * Stage 4: Blue/Green 배포
     * - Blue Service 상태 확인
     * - Green Task 실행
     * - Lifecycle Hooks 처리
     */
    public void performBlueGreenDeployment(String deploymentId, StageEventHelper stageHelper,
                                          String blueUrl, String greenUrl, AwsConnection awsConnection) throws Exception {
        EcsClient ecsClient = createEcsClient(awsConnection);
        try {
            String clusterName = "panda-cluster";

            // 1. Blue Service 상태 확인
            stageHelper.stage4BlueServiceRunning(blueUrl);
            log.info("Blue service running at {}", blueUrl);

            // 2. Green Service 생성 (새로운 태스크 또는 복제)
            stageHelper.stage4GreenServiceSpinning(greenUrl);

            // Green Service를 위한 새 Task Definition 버전
            DescribeServicesResponse servicesResponse = ecsClient.describeServices(
                    DescribeServicesRequest.builder()
                            .cluster(clusterName)
                            .services("panda-service")
                            .build()
            );

            if (!servicesResponse.services().isEmpty()) {
                software.amazon.awssdk.services.ecs.model.Service service = servicesResponse.services().get(0);
                String taskDefinitionArn = service.taskDefinition();

                if (taskDefinitionArn != null && !taskDefinitionArn.isEmpty()) {
                    // Green 버전의 ECS Task 실행
                    RunTaskResponse runTaskResponse = ecsClient.runTask(
                            RunTaskRequest.builder()
                                    .cluster(clusterName)
                                    .taskDefinition(taskDefinitionArn)
                                    .launchType("EC2")
                                    .count(1)
                                    .build()
                    );

                    if (!runTaskResponse.tasks().isEmpty()) {
                        log.info("Green task started: {}", runTaskResponse.tasks().get(0).taskArn());
                    }
                }
            }

            Thread.sleep(2000);  // Green service 시작 대기
            stageHelper.stage4GreenServiceReady(greenUrl);
            log.info("Green service ready at {}", greenUrl);

            // 3. Lifecycle Hooks 실행
            stageHelper.stage4LifecycleHook("BeforeAllowTraffic");
            log.info("BeforeAllowTraffic validation running");

            Thread.sleep(1000);

            stageHelper.stage4LifecycleHook("AfterAllowTraffic");
            log.info("AfterAllowTraffic preparation running");

            log.info("Blue/Green deployment completed for deploymentId: {}", deploymentId);
        } catch (EcsException e) {
            log.error("ECS error during Blue/Green deployment: {}", e.getMessage());
            throw new RuntimeException("Blue/Green deployment failed: " + e.getMessage());
        } finally {
            ecsClient.close();
        }
    }

    /**
     * ECS Client 생성
     */
    protected EcsClient createEcsClient(AwsConnection awsConnection) {
        if (awsConnection.getSessionToken() != null && !awsConnection.getSessionToken().isEmpty()) {
            AwsSessionCredentials credentials = AwsSessionCredentials.create(
                    awsConnection.getAccessKeyId(),
                    awsConnection.getSecretAccessKey(),
                    awsConnection.getSessionToken()
            );
            return EcsClient.builder()
                    .region(Region.of(awsConnection.getRegion()))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .build();
        } else {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(
                    awsConnection.getAccessKeyId(),
                    awsConnection.getSecretAccessKey()
            );
            return EcsClient.builder()
                    .region(Region.of(awsConnection.getRegion()))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .build();
        }
    }
}
