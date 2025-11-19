package com.panda.backend.feature.connect.infrastructure;

import com.panda.backend.feature.connect.entity.AwsConnection;
import com.panda.backend.feature.connect.entity.GitHubConnection;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import software.amazon.awssdk.services.secretsmanager.model.PutSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.CreateSecretRequest;
import software.amazon.awssdk.services.secretsmanager.model.ResourceNotFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
public class ConnectionStore {
    // AWS Secrets Manager를 사용하여 토큰 보안 저장
    // GitHub Token과 AWS Credentials를 Secrets Manager에 저장
    // connectionId는 메모리에 저장하여 조회 가능하게 함

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;

    // connectionId -> secret name 매핑 (메모리)
    // TODO: 앱 재시작 후에도 저장된 연결 정보를 조회할 수 있도록 RDS/DynamoDB에 connectionId -> secretName 매핑을 영속화하기
    private final Map<String, String> gitHubConnectionSecrets = new HashMap<>();  // gh_xxx -> secret-name
    private final Map<String, String> awsConnectionSecrets = new HashMap<>();     // aws_xxx -> secret-name

    public ConnectionStore(SecretsManagerClient secretsManagerClient, ObjectMapper objectMapper) {
        this.secretsManagerClient = secretsManagerClient;
        this.objectMapper = objectMapper;
    }

    /**
     * GitHub 연결 정보를 AWS Secrets Manager에 저장
     * TODO: IAM 권한 확인 - SecretsManager:CreateSecret, SecretsManager:PutSecretValue, SecretsManager:GetSecretValue 권한 필요
     * TODO: KMS 암호화 키 설정 (기본값: AWS managed key 사용, 프로덕션: 고객 관리 키 권장)
     */
    public String saveGitHubConnection(GitHubConnection connection) {
        String connectionId = "gh_" + UUID.randomUUID().toString().substring(0, 10);
        String secretName = "panda/github/" + connectionId;

        try {
            // GitHub 연결 정보를 JSON으로 직렬화
            String secretValue = objectMapper.writeValueAsString(connection);

            // Secrets Manager에 저장
            try {
                PutSecretValueRequest putSecretRequest = PutSecretValueRequest.builder()
                        .secretId(secretName)
                        .secretString(secretValue)
                        .build();
                secretsManagerClient.putSecretValue(putSecretRequest);
                log.info("GitHub connection saved to Secrets Manager: {}", secretName);
            } catch (ResourceNotFoundException e) {
                // Secret이 없으면 새로 생성
                CreateSecretRequest createSecretRequest = CreateSecretRequest.builder()
                        .name(secretName)
                        .secretString(secretValue)
                        .build();
                secretsManagerClient.createSecret(createSecretRequest);
                log.info("GitHub connection created in Secrets Manager: {}", secretName);
            }

            // connectionId -> secretName 매핑만 메모리에 저장
            gitHubConnectionSecrets.put(connectionId, secretName);
            return connectionId;

        } catch (Exception e) {
            log.error("Failed to save GitHub connection to Secrets Manager", e);
            throw new RuntimeException("Failed to save GitHub connection: " + e.getMessage(), e);
        }
    }

    /**
     * AWS 연결 정보를 AWS Secrets Manager에 저장
     * TODO: IAM 권한 확인 - SecretsManager:CreateSecret, SecretsManager:PutSecretValue, SecretsManager:GetSecretValue 권한 필요
     * TODO: AWS 자격증명 기한 만료 전 알림 및 자동 갱신 메커니즘 구현
     */
    public String saveAwsConnection(AwsConnection connection) {
        String connectionId = "aws_" + UUID.randomUUID().toString().substring(0, 10);
        String secretName = "panda/aws/" + connectionId;

        try {
            // AWS 연결 정보를 JSON으로 직렬화
            String secretValue = objectMapper.writeValueAsString(connection);

            // Secrets Manager에 저장
            try {
                PutSecretValueRequest putSecretRequest = PutSecretValueRequest.builder()
                        .secretId(secretName)
                        .secretString(secretValue)
                        .build();
                secretsManagerClient.putSecretValue(putSecretRequest);
                log.info("AWS connection saved to Secrets Manager: {}", secretName);
            } catch (ResourceNotFoundException e) {
                // Secret이 없으면 새로 생성
                CreateSecretRequest createSecretRequest = CreateSecretRequest.builder()
                        .name(secretName)
                        .secretString(secretValue)
                        .build();
                secretsManagerClient.createSecret(createSecretRequest);
                log.info("AWS connection created in Secrets Manager: {}", secretName);
            }

            // connectionId -> secretName 매핑만 메모리에 저장
            awsConnectionSecrets.put(connectionId, secretName);
            return connectionId;

        } catch (Exception e) {
            log.error("Failed to save AWS connection to Secrets Manager", e);
            throw new RuntimeException("Failed to save AWS connection: " + e.getMessage(), e);
        }
    }

    /**
     * GitHub 연결 정보를 Secrets Manager에서 조회
     */
    public Optional<GitHubConnection> getGitHubConnection(String connectionId) {
        try {
            String secretName = gitHubConnectionSecrets.get(connectionId);
            if (secretName == null) {
                log.warn("GitHub connection not found: {}", connectionId);
                return Optional.empty();
            }

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);

            // JSON을 GitHubConnection으로 역직렬화
            GitHubConnection connection = objectMapper.readValue(
                response.secretString(),
                GitHubConnection.class
            );

            return Optional.of(connection);

        } catch (ResourceNotFoundException e) {
            log.warn("Secret not found in Secrets Manager: {}", connectionId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to retrieve GitHub connection from Secrets Manager", e);
            return Optional.empty();
        }
    }

    /**
     * AWS 연결 정보를 Secrets Manager에서 조회
     */
    public Optional<AwsConnection> getAwsConnection(String connectionId) {
        try {
            String secretName = awsConnectionSecrets.get(connectionId);
            if (secretName == null) {
                log.warn("AWS connection not found: {}", connectionId);
                return Optional.empty();
            }

            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();
            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);

            // JSON을 AwsConnection으로 역직렬화
            AwsConnection connection = objectMapper.readValue(
                response.secretString(),
                AwsConnection.class
            );

            return Optional.of(connection);

        } catch (ResourceNotFoundException e) {
            log.warn("Secret not found in Secrets Manager: {}", connectionId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to retrieve AWS connection from Secrets Manager", e);
            return Optional.empty();
        }
    }

    public Map<String, GitHubConnection> getAllGitHubConnections() {
        // 프로토타입: 모든 secret을 조회하는 것은 비용이 높으므로, 메모리의 ID만 반환
        Map<String, GitHubConnection> result = new HashMap<>();
        for (String connectionId : gitHubConnectionSecrets.keySet()) {
            getGitHubConnection(connectionId).ifPresent(conn -> result.put(connectionId, conn));
        }
        return result;
    }

    public Map<String, AwsConnection> getAllAwsConnections() {
        // 프로토타입: 모든 secret을 조회하는 것은 비용이 높으므로, 메모리의 ID만 반환
        Map<String, AwsConnection> result = new HashMap<>();
        for (String connectionId : awsConnectionSecrets.keySet()) {
            getAwsConnection(connectionId).ifPresent(conn -> result.put(connectionId, conn));
        }
        return result;
    }
}
