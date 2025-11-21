package com.panda.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.sfn.SfnClient;

/**
 * AWS 클라이언트 설정
 * - Step Functions (ExecutionHistory 조회)
 * - Lambda (서비스 계정 Lambda 호출)
 *
 * NOTE: SecretsManagerClient는 AwsConfig에서 정의
 *       ConnectionStore와 ExecutionArnStore에서 공유 사용
 */
@Slf4j
@Configuration
public class AwsStepFunctionsConfig {

    /**
     * AWS Step Functions 클라이언트 빈
     * ExecutionHistory 조회에 사용
     */
    @Bean
    public SfnClient sfnClient() {
        try {
            SfnClient client = SfnClient.builder()
                .region(Region.US_EAST_1)  // 기본 region, 필요시 application.yml에서 설정 가능
                .build();

            log.info("AWS Step Functions client initialized");
            return client;

        } catch (Exception e) {
            log.error("Failed to initialize AWS Step Functions client", e);
            throw new RuntimeException("Failed to initialize AWS Step Functions client", e);
        }
    }

    /**
     * AWS Lambda 클라이언트 빈
     * 서비스 계정의 Lambda 함수 호출에 사용
     */
    @Bean
    public LambdaClient lambdaClient() {
        try {
            LambdaClient client = LambdaClient.builder()
                .region(Region.AP_NORTHEAST_2)  // 서비스 계정의 Region
                .build();

            log.info("AWS Lambda client initialized");
            return client;

        } catch (Exception e) {
            log.error("Failed to initialize AWS Lambda client", e);
            throw new RuntimeException("Failed to initialize AWS Lambda client", e);
        }
    }
}
