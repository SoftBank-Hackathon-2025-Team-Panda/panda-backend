package com.panda.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.sfn.SfnClient;

/**
 * AWS 클라이언트 설정
 * - Step Functions (ExecutionHistory 조회)
 * - Lambda (서비스 계정 Lambda 호출)
 * - ECS (Service 및 LoadBalancer 정보 조회)
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
                .region(Region.AP_NORTHEAST_2)  // ap-northeast-2로 고정
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

    /**
     * AWS ECS 클라이언트 빈
     * 사용자 AWS 계정의 ECS Service 및 LoadBalancer 정보 조회에 사용
     *
     * NOTE: 사용자 AWS 자격증명으로 클라이언트를 새로 생성하므로
     *       여기서는 최소한의 기본 클라이언트만 제공
     */
    @Bean
    public EcsClient ecsClient() {
        try {
            EcsClient client = EcsClient.builder()
                .region(Region.AP_NORTHEAST_2)  // 기본 Region, 사용 시 동적으로 변경 가능
                .build();

            log.info("AWS ECS client initialized");
            return client;

        } catch (Exception e) {
            log.error("Failed to initialize AWS ECS client", e);
            throw new RuntimeException("Failed to initialize AWS ECS client", e);
        }
    }
}
