package com.panda.backend.feature.deploy.infrastructure;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.*;

/**
 * Step Functions의 ExecutionArn을 AWS Secrets Manager에 저장/조회하는 컴포넌트
 *
 * 사용 흐름:
 * 1. Step Functions 내부 Lambda가 ExecutionArn을 저장: save(deploymentId, executionArn)
 * 2. 백엔드 폴링 서비스가 조회: get(deploymentId)
 * 3. 배포 완료 후 정리: remove(deploymentId)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionArnStore {

    private final SecretsManagerClient secretsManagerClient;

    @Value("${aws.secrets-manager.execution-arn-prefix:panda/deployment/}")
    private String secretPrefix;

    /**
     * ExecutionArn을 Secrets Manager에 저장
     * (Step Functions 내부의 Lambda에서 호출됨)
     *
     * @param deploymentId 배포 ID (사용자 백엔드에서 생성)
     * @param executionArn Step Functions Execution ARN
     */
    public void save(String deploymentId, String executionArn) {
        try {
            String secretName = secretPrefix + deploymentId;

            PutSecretValueRequest request = PutSecretValueRequest.builder()
                .secretId(secretName)
                .secretString(executionArn)
                .build();

            secretsManagerClient.putSecretValue(request);

            log.info("ExecutionArn saved to Secrets Manager - secretName: {}, executionArn: {}",
                secretName, executionArn);

        } catch (Exception e) {
            log.error("Failed to save ExecutionArn to Secrets Manager for deploymentId: {}",
                deploymentId, e);
            throw new RuntimeException("Failed to save ExecutionArn: " + e.getMessage(), e);
        }
    }

    /**
     * Secrets Manager에서 ExecutionArn 조회
     * (백엔드의 폴링 서비스에서 호출됨)
     *
     * @param deploymentId 배포 ID
     * @return ExecutionArn (없으면 null)
     */
    public String get(String deploymentId) {
        try {
            String secretName = secretPrefix + deploymentId;

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                .secretId(secretName)
                .build();

            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);

            log.debug("ExecutionArn retrieved from Secrets Manager - secretName: {}", secretName);

            return response.secretString();

        } catch (ResourceNotFoundException e) {
            log.debug("ExecutionArn not found in Secrets Manager - deploymentId: {}", deploymentId);
            return null;
        } catch (Exception e) {
            log.error("Failed to get ExecutionArn from Secrets Manager for deploymentId: {}",
                deploymentId, e);
            throw new RuntimeException("Failed to get ExecutionArn: " + e.getMessage(), e);
        }
    }

    /**
     * Secrets Manager에서 ExecutionArn 삭제
     * (배포 완료 후 호출되어 정리함)
     *
     * @param deploymentId 배포 ID
     */
    public void remove(String deploymentId) {
        try {
            String secretName = secretPrefix + deploymentId;

            DeleteSecretRequest request = DeleteSecretRequest.builder()
                .secretId(secretName)
                .forceDeleteWithoutRecovery(true)
                .build();

            secretsManagerClient.deleteSecret(request);

            log.info("ExecutionArn deleted from Secrets Manager - secretName: {}", secretName);

        } catch (ResourceNotFoundException e) {
            log.debug("ExecutionArn already deleted or not found - deploymentId: {}", deploymentId);
        } catch (Exception e) {
            log.warn("Failed to delete ExecutionArn from Secrets Manager for deploymentId: {}, error: {}",
                deploymentId, e.getMessage());
            // 삭제 실패는 치명적이지 않으므로 exception 던지지 않음
        }
    }

    /**
     * Secrets Manager에 ExecutionArn이 존재하는지 확인
     *
     * @param deploymentId 배포 ID
     * @return 존재하면 true, 없으면 false
     */
    public boolean exists(String deploymentId) {
        return get(deploymentId) != null;
    }
}
