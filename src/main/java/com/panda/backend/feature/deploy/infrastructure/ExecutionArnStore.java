package com.panda.backend.feature.deploy.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.*;

import java.util.Map;

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
    private final ObjectMapper objectMapper;

    @Value("${aws.secrets-manager.execution-arn-prefix:panda/stepfunctions/}")
    private String secretPrefix;

    /**
     * ExecutionArn을 Secrets Manager에 저장
     * (Step Functions 내부의 Lambda에서 호출됨)
     *
     * @param owner GitHub owner
     * @param repo GitHub repo
     * @param executionArn Step Functions Execution ARN
     */
    public void save(String owner, String repo, String executionArn) {
        try {
            String secretName = secretPrefix + owner.toLowerCase() + "-" + repo.toLowerCase() + "-latest-execution";

            PutSecretValueRequest request = PutSecretValueRequest.builder()
                .secretId(secretName)
                .secretString(executionArn)
                .build();

            secretsManagerClient.putSecretValue(request);

            log.info("ExecutionArn saved to Secrets Manager - secretName: {}, executionArn: {}",
                secretName, executionArn);

        } catch (Exception e) {
            log.error("Failed to save ExecutionArn to Secrets Manager for owner: {}, repo: {}",
                owner, repo, e);
            throw new RuntimeException("Failed to save ExecutionArn: " + e.getMessage(), e);
        }
    }

    /**
     * Secrets Manager에서 ExecutionArn 조회
     * (백엔드의 폴링 서비스에서 호출됨)
     *
     * @param owner GitHub owner
     * @param repo GitHub repo
     * @return ExecutionArn (없으면 null)
     */
    public String get(String owner, String repo) {
        try {
            String secretName = secretPrefix + owner.toLowerCase() + "-" + repo.toLowerCase() + "-latest-execution";

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                .secretId(secretName)
                .build();

            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            String secretValue = response.secretString();

            log.debug("ExecutionArn retrieved from Secrets Manager - secretName: {}", secretName);

            // JSON 형식인지 확인하고 executionArn 필드 추출
            if (secretValue != null && secretValue.trim().startsWith("{")) {
                try {
                    Map<String, Object> jsonMap = objectMapper.readValue(secretValue, Map.class);
                    if (jsonMap.containsKey("executionArn")) {
                        String executionArn = (String) jsonMap.get("executionArn");
                        log.debug("Extracted executionArn from JSON: {}", executionArn);
                        return executionArn;
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse secret as JSON, returning as-is: {}", e.getMessage());
                }
            }

            return secretValue;

        } catch (ResourceNotFoundException e) {
            log.debug("ExecutionArn not found in Secrets Manager - owner: {}, repo: {}", owner, repo);
            return null;
        } catch (Exception e) {
            log.error("Failed to get ExecutionArn from Secrets Manager for owner: {}, repo: {}",
                owner, repo, e);
            throw new RuntimeException("Failed to get ExecutionArn: " + e.getMessage(), e);
        }
    }

    /**
     * Secrets Manager에서 ExecutionArn 삭제
     * (배포 완료 후 호출되어 정리함)
     *
     * @param owner GitHub owner
     * @param repo GitHub repo
     */
    public void remove(String owner, String repo) {
        try {
            String secretName = secretPrefix + owner.toLowerCase() + "-" + repo.toLowerCase() + "-latest-execution";

            DeleteSecretRequest request = DeleteSecretRequest.builder()
                .secretId(secretName)
                .forceDeleteWithoutRecovery(true)
                .build();

            secretsManagerClient.deleteSecret(request);

            log.info("ExecutionArn deleted from Secrets Manager - secretName: {}", secretName);

        } catch (ResourceNotFoundException e) {
            log.debug("ExecutionArn already deleted or not found - owner: {}, repo: {}", owner, repo);
        } catch (Exception e) {
            log.warn("Failed to delete ExecutionArn from Secrets Manager for owner: {}, repo: {}, error: {}",
                owner, repo, e.getMessage());
            // 삭제 실패는 치명적이지 않으므로 exception 던지지 않음
        }
    }

    /**
     * Secrets Manager에 ExecutionArn이 존재하는지 확인
     *
     * @param owner GitHub owner
     * @param repo GitHub repo
     * @return 존재하면 true, 없으면 false
     */
    public boolean exists(String owner, String repo) {
        return get(owner, repo) != null;
    }
}
