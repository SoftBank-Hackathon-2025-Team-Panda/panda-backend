package com.panda.backend.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

@Configuration
public class AwsConfig {

    @Value("${aws.region:ap-northeast-2}")
    private String awsRegion;

    @Value("${AWS_ACCESS_KEY_ID:}")
    private String accessKeyId;

    @Value("${AWS_SECRET_ACCESS_KEY:}")
    private String secretAccessKey;

    /**
     * AWS Secrets Manager 클라이언트 Bean 등록
     * ConnectionStore에서 GitHub Token과 AWS Credentials를 암호화하여 저장하는 데 사용
     *
     * 환경 변수:
     * - AWS_REGION: AWS 리전 (기본값: ap-northeast-2)
     * - AWS_ACCESS_KEY_ID: AWS 액세스 키
     * - AWS_SECRET_ACCESS_KEY: AWS 시크릿 키
     *
     * TODO: 프로덕션 환경에서는 고객 관리 KMS 키(CMK)로 암호화 설정
     * TODO: Secrets Manager 연결 실패 시 폴백 메커니즘 또는 재시도 로직 추가
     * TODO: 환경 변수 누락 시 IAM Role을 이용한 자동 인증 지원
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        // 환경 변수에서 자격증명이 있으면 사용, 없으면 AWS SDK 기본 자격증명 체인 사용
        if (!accessKeyId.isEmpty() && !secretAccessKey.isEmpty()) {
            return SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKeyId, secretAccessKey)
                    ))
                    .build();
        } else {
            // IAM Role이 있는 환경 (EC2, ECS, Lambda 등)에서 사용
            return SecretsManagerClient.builder()
                    .region(Region.of(awsRegion))
                    .build();
        }
    }
}
