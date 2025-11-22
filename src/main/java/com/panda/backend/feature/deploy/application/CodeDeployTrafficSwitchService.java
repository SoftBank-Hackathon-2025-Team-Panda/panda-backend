package com.panda.backend.feature.deploy.application;

import com.panda.backend.feature.connect.entity.AwsConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.codedeploy.CodeDeployClient;
import software.amazon.awssdk.services.codedeploy.model.ContinueDeploymentRequest;

/**
 * CodeDeploy를 사용하여 Blue/Green 배포의 트래픽을 전환하는 서비스
 *
 * 흐름:
 * 1. HealthCheck 성공 후 ContinueDeployment API 호출
 * 2. CodeDeploy의 "AfterAllowTraffic" lifecycle hook 완료
 * 3. 트래픽이 Green으로 전환됨
 *
 * 호출 위치:
 * - HealthCheckService에서 HealthCheck 성공 후
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeDeployTrafficSwitchService {

    /**
     * CodeDeploy 배포의 트래픽 전환 승인
     *
     * HealthCheck 성공 후 호출되어 "AfterAllowTraffic" lifecycle hook을 완료하고
     * 트래픽을 Green으로 전환함
     *
     * @param deploymentId Panda 배포 ID
     * @param codeDeployDeploymentId CodeDeploy 배포 ID
     * @param codeDeployApplicationName CodeDeploy 애플리케이션명
     * @param awsConnection 사용자 AWS 연결 정보
     * @throws Exception 트래픽 전환 실패 시
     */
    public void approveTrafficSwitch(String deploymentId,
                                     String codeDeployDeploymentId,
                                     String codeDeployApplicationName,
                                     AwsConnection awsConnection) throws Exception {
        if (codeDeployDeploymentId == null || codeDeployDeploymentId.isEmpty()) {
            log.warn("CodeDeploy deployment ID not available, skipping traffic switch approval");
            return;
        }

        CodeDeployClient codeDeployClient = createCodeDeployClient(awsConnection);

        try {
            log.info("Approving traffic switch for CodeDeploy deployment: {} (Panda deploymentId: {})",
                codeDeployDeploymentId, deploymentId);

            // CodeDeploy에 트래픽 전환 승인 신호 전송
            // ContinueDeployment API는 AfterAllowTraffic lifecycle hook을 완료하고
            // 트래픽 전환을 계속 진행하도록 함
            ContinueDeploymentRequest request = ContinueDeploymentRequest.builder()
                .deploymentId(codeDeployDeploymentId)
                .build();

            codeDeployClient.continueDeployment(request);

            log.info("Traffic switch approved successfully for CodeDeploy deployment: {}",
                codeDeployDeploymentId);

        } catch (Exception e) {
            log.error("Failed to approve traffic switch for CodeDeploy deployment: {}",
                codeDeployDeploymentId, e);
            throw new RuntimeException("Failed to approve traffic switch: " + e.getMessage(), e);
        } finally {
            codeDeployClient.close();
        }
    }

    /**
     * CodeDeploy 클라이언트 생성
     */
    private CodeDeployClient createCodeDeployClient(AwsConnection awsConnection) {
        try {
            Region region = Region.of(awsConnection.getRegion());

            StaticCredentialsProvider credentialsProvider;

            if (awsConnection.getSessionToken() != null && !awsConnection.getSessionToken().isEmpty()) {
                AwsSessionCredentials sessionCredentials = AwsSessionCredentials.create(
                    awsConnection.getAccessKeyId(),
                    awsConnection.getSecretAccessKey(),
                    awsConnection.getSessionToken()
                );
                credentialsProvider = StaticCredentialsProvider.create(sessionCredentials);
            } else {
                AwsBasicCredentials basicCredentials = AwsBasicCredentials.create(
                    awsConnection.getAccessKeyId(),
                    awsConnection.getSecretAccessKey()
                );
                credentialsProvider = StaticCredentialsProvider.create(basicCredentials);
            }

            return CodeDeployClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();

        } catch (Exception e) {
            log.error("Failed to create CodeDeploy client", e);
            throw new RuntimeException("Failed to create CodeDeploy client: " + e.getMessage(), e);
        }
    }
}
