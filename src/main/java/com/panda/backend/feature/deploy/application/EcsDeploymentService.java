package com.panda.backend.feature.deploy.application;

import com.panda.backend.feature.connect.entity.AwsConnection;
import com.panda.backend.feature.deploy.event.StageEventHelper;
import com.panda.backend.feature.deploy.exception.EcsDeploymentException;
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
public class EcsDeploymentService {

    /**
     * Stage 3: ECS 배포
     * - Cluster 생성/확인
     * - Task Definition 등록
     * - Service 생성/업데이트
     * - Service 상태 대기
     */
    public void performEcsDeployment(String deploymentId, StageEventHelper stageHelper, String ecrImageUri, AwsConnection awsConnection) throws Exception {
        EcsClient ecsClient = createEcsClient(awsConnection);
        try {
            String clusterName = "panda-cluster";
            String serviceName = "panda-service";
            String taskDefinitionFamily = "panda-task";

            // 1. ECS Cluster 확인/생성
            try {
                ecsClient.describeClusters(DescribeClustersRequest.builder()
                        .clusters(clusterName)
                        .build());
                log.info("ECS Cluster {} already exists", clusterName);
            } catch (Exception e) {
                ecsClient.createCluster(CreateClusterRequest.builder()
                        .clusterName(clusterName)
                        .build());
                log.info("ECS Cluster {} created", clusterName);
            }

            // 2. Task Definition 등록
            RegisterTaskDefinitionResponse taskDefResponse = ecsClient.registerTaskDefinition(
                    RegisterTaskDefinitionRequest.builder()
                            .family(taskDefinitionFamily)
                            .containerDefinitions(ContainerDefinition.builder()
                                    .name("panda-app")
                                    .image(ecrImageUri)
                                    .memory(512)
                                    .cpu(256)
                                    .essential(true)
                                    .portMappings(PortMapping.builder()
                                            .containerPort(8080)
                                            .hostPort(8080)
                                            .build())
                                    .build())
                            .requiresCompatibilities(Compatibility.EC2)
                            .build()
            );
            log.info("Task Definition {} registered: {}", taskDefinitionFamily, taskDefResponse.taskDefinition().taskDefinitionArn());

            // 3. Service 생성/업데이트
            try {
                ecsClient.describeServices(DescribeServicesRequest.builder()
                        .cluster(clusterName)
                        .services(serviceName)
                        .build());

                // Service 존재 - 업데이트
                ecsClient.updateService(UpdateServiceRequest.builder()
                        .cluster(clusterName)
                        .service(serviceName)
                        .taskDefinition(taskDefResponse.taskDefinition().taskDefinitionArn())
                        .build());
                stageHelper.stage3ServiceUpdated(serviceName);
                log.info("ECS Service {} updated", serviceName);
            } catch (Exception e) {
                // Service 없음 - 생성
                ecsClient.createService(CreateServiceRequest.builder()
                        .cluster(clusterName)
                        .serviceName(serviceName)
                        .taskDefinition(taskDefResponse.taskDefinition().taskDefinitionArn())
                        .desiredCount(1)
                        .build());
                stageHelper.stage3ServiceCreated(serviceName, clusterName);
                log.info("ECS Service {} created", serviceName);
            }

            // 4. Service 상태 대기 (최대 5분)
            waitForServiceDeployment(ecsClient, clusterName, serviceName);

            log.info("ECS deployment completed for deploymentId: {}", deploymentId);
        } catch (EcsException e) {
            log.error("ECS error during deployment: {}", e.getMessage());
            throw new EcsDeploymentException("ECS deployment failed: " + e.getMessage(), deploymentId);
        } finally {
            ecsClient.close();
        }
    }

    /**
     * Service가 활성화될 때까지 대기
     */
    private void waitForServiceDeployment(EcsClient ecsClient, String clusterName, String serviceName) throws Exception {
        long maxWaitTime = System.currentTimeMillis() + (5 * 60 * 1000);  // 5분
        boolean serviceActive = false;

        while (System.currentTimeMillis() < maxWaitTime) {
            DescribeServicesResponse response = ecsClient.describeServices(
                    DescribeServicesRequest.builder()
                            .cluster(clusterName)
                            .services(serviceName)
                            .build()
            );

            if (!response.services().isEmpty()) {
                software.amazon.awssdk.services.ecs.model.Service service = response.services().get(0);
                int desiredCount = service.desiredCount() != null ? service.desiredCount() : 0;

                // Deployment의 runningCount 확인
                int runningCount = 0;
                if (service.deployments() != null && !service.deployments().isEmpty()) {
                    Deployment deployment = service.deployments().get(0);
                    runningCount = deployment.runningCount() != null ? deployment.runningCount() : 0;
                }

                if (desiredCount == runningCount && runningCount > 0) {
                    serviceActive = true;
                    log.info("ECS Service {} is running with {} tasks", serviceName, runningCount);
                    break;
                }
            }

            Thread.sleep(5000);  // 5초 대기 후 다시 확인
        }

        if (!serviceActive) {
            throw new EcsDeploymentException("ECS Service did not reach active state", null);
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
