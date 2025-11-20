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

import java.util.Optional;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;

@Slf4j
@Component
public class ConnectionStore {
    // AWS Secrets Manager를 사용하여 토큰 보안 저장
    // GitHub Token과 AWS Credentials를 Secrets Manager에 암호화 저장
    // secretName은 naming rule로 자동 계산: panda/github/{connectionId}, panda/aws/{connectionId}

    private final SecretsManagerClient secretsManagerClient;
    private final ObjectMapper objectMapper;

    // GitHub/AWS 연결 메타데이터 저장 (메모리)
    // connectionId -> {owner, repo, branch} 또는 {region}
    protected Map<String, Map<String, String>> gitHubConnectionMetadata = new HashMap<>();
    protected Map<String, Map<String, String>> awsConnectionMetadata = new HashMap<>();

    public ConnectionStore(SecretsManagerClient secretsManagerClient, ObjectMapper objectMapper) {
        this.secretsManagerClient = secretsManagerClient;
        this.objectMapper = objectMapper;
    }

    /**
     * GitHub 연결 정보를 AWS Secrets Manager에 저장
     * 메타데이터(owner, repo, branch)를 메모리에 저장하여 GET API에서 반환
     * TODO: IAM 권한 확인 - SecretsManager:CreateSecret, SecretsManager:PutSecretValue, SecretsManager:GetSecretValue 권한 필요
     * TODO: KMS 암호화 키 설정 (기본값: AWS managed key 사용, 프로덕션: 고객 관리 키 권장)
     */
    public String saveGitHubConnection(GitHubConnection connection, String owner, String repo, String branch) {
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

            // 메타데이터 저장
            Map<String, String> metadata = new HashMap<>();
            metadata.put("owner", owner);
            metadata.put("repo", repo);
            metadata.put("branch", branch);
            gitHubConnectionMetadata.put(connectionId, metadata);
            log.info("GitHub connection metadata stored: {} -> owner={}, repo={}, branch={}",
                    connectionId, owner, repo, branch);

            return connectionId;

        } catch (Exception e) {
            log.error("Failed to save GitHub connection to Secrets Manager", e);
            throw new RuntimeException("Failed to save GitHub connection: " + e.getMessage(), e);
        }
    }

    /**
     * AWS 연결 정보를 AWS Secrets Manager에 저장
     * 메타데이터(region)를 메모리에 저장하여 GET API에서 반환
     * TODO: IAM 권한 확인 - SecretsManager:CreateSecret, SecretsManager:PutSecretValue, SecretsManager:GetSecretValue 권한 필요
     * TODO: AWS 자격증명 기한 만료 전 알림 및 자동 갱신 메커니즘 구현
     */
    public String saveAwsConnection(AwsConnection connection, String region) {
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

            // 메타데이터 저장
            Map<String, String> metadata = new HashMap<>();
            metadata.put("region", region);
            awsConnectionMetadata.put(connectionId, metadata);
            log.info("AWS connection metadata stored: {} -> region={}", connectionId, region);

            return connectionId;

        } catch (Exception e) {
            log.error("Failed to save AWS connection to Secrets Manager", e);
            throw new RuntimeException("Failed to save AWS connection: " + e.getMessage(), e);
        }
    }

    /**
     * GitHub 연결 정보를 Secrets Manager에서 조회
     * secretName은 명확한 naming rule로 자동 계산
     */
    public Optional<GitHubConnection> getGitHubConnection(String connectionId) {
        try {
            // naming rule로 secretName 자동 계산
            String secretName = "panda/github/" + connectionId;

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
            log.warn("GitHub connection not found in Secrets Manager: {}", connectionId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to retrieve GitHub connection from Secrets Manager", e);
            return Optional.empty();
        }
    }

    /**
     * AWS 연결 정보를 Secrets Manager에서 조회
     * secretName은 명확한 naming rule로 자동 계산
     */
    public Optional<AwsConnection> getAwsConnection(String connectionId) {
        try {
            // naming rule로 secretName 자동 계산
            String secretName = "panda/aws/" + connectionId;

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
            log.warn("AWS connection not found in Secrets Manager: {}", connectionId);
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to retrieve AWS connection from Secrets Manager", e);
            return Optional.empty();
        }
    }

    /**
     * 저장된 모든 GitHub 연결 정보 반환
     * connectionId와 메타데이터(owner, repo, branch) 포함
     */
    public Map<String, Map<String, String>> getAllGitHubConnections() {
        return new HashMap<>(gitHubConnectionMetadata);
    }

    /**
     * 저장된 모든 AWS 연결 정보 반환
     * connectionId와 메타데이터(region) 포함
     */
    public Map<String, Map<String, String>> getAllAwsConnections() {
        return new HashMap<>(awsConnectionMetadata);
    }

    /**
     * 특정 GitHub 연결의 메타데이터 조회
     */
    public Optional<Map<String, String>> getGitHubConnectionMetadata(String connectionId) {
        return Optional.ofNullable(gitHubConnectionMetadata.get(connectionId));
    }

    /**
     * 특정 AWS 연결의 메타데이터 조회
     */
    public Optional<Map<String, String>> getAwsConnectionMetadata(String connectionId) {
        return Optional.ofNullable(awsConnectionMetadata.get(connectionId));
    }

}
