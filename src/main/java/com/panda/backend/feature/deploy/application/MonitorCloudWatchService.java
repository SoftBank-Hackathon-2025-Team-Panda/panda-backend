package com.panda.backend.feature.deploy.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panda.backend.feature.connect.entity.AwsConnection;
import com.panda.backend.feature.deploy.dto.MonitorCloudWatchRequest;
import com.panda.backend.feature.deploy.dto.MonitorCloudWatchResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

import java.nio.charset.StandardCharsets;

/**
 * 사용자 AWS 계정의 CloudWatch 메트릭 모니터링 Lambda를 호출하는 서비스
 *
 * 흐름:
 * 1. Blue/Green 서비스의 ECS 서비스 ARN을 기반으로 Lambda 호출
 * 2. Lambda가 CloudWatch 메트릭 수집 (Latency, Error Rate, CPU, Memory 등)
 * 3. 메트릭 데이터 반환
 *
 * 호출 위치:
 * - Stage 4 (Blue/Green 배포) 이후
 * - Stage 5 (HealthCheck) 중에 주기적으로 호출
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorCloudWatchService {

    private final ObjectMapper objectMapper;

    @Value("${aws.lambda.monitor-cloudwatch-function-name:lambda_1_monitor_cloudwatch}")
    private String monitorCloudWatchLambdaName;

    @Value("${aws.lambda.monitor-interval-seconds:30}")
    private long monitorIntervalSeconds;

    /**
     * 사용자 AWS 계정에서 CloudWatch 메트릭 수집 Lambda 호출
     *
     * @param deploymentId 배포 ID
     * @param awsConnection 사용자 AWS 연결 정보
     * @param blueServiceArn Blue 서비스 ARN
     * @param greenServiceArn Green 서비스 ARN
     * @param clusterName ECS 클러스터명
     * @param serviceName ECS 서비스명
     * @return CloudWatch 메트릭 응답
     * @throws Exception Lambda 호출 실패 시
     */
    public MonitorCloudWatchResponse invokeCloudWatchMonitoring(
            String deploymentId,
            AwsConnection awsConnection,
            String blueServiceArn,
            String greenServiceArn,
            String clusterName,
            String serviceName) throws Exception {

        try {
            log.info("Invoking CloudWatch monitoring Lambda for deploymentId: {} in region: {}",
                deploymentId, awsConnection.getRegion());

            // 사용자 AWS 자격증명으로 Lambda 클라이언트 생성
            LambdaClient userLambdaClient = createUserLambdaClient(awsConnection);

            try {
                // 요청 생성
                MonitorCloudWatchRequest request = MonitorCloudWatchRequest.builder()
                    .deploymentId(deploymentId)
                    .blueServiceArn(blueServiceArn)
                    .greenServiceArn(greenServiceArn)
                    .clusterName(clusterName)
                    .serviceName(serviceName)
                    .minutesRange(5)  // 최근 5분 메트릭
                    .statisticType("Average")
                    .build();

                String payload = objectMapper.writeValueAsString(request);

                log.debug("CloudWatch Lambda invocation payload: {}", payload);

                // Lambda 호출 요청
                InvokeRequest invokeRequest = InvokeRequest.builder()
                    .functionName(monitorCloudWatchLambdaName)
                    .invocationType("RequestResponse")  // 동기 호출
                    .payload(SdkBytes.fromString(payload, StandardCharsets.UTF_8))
                    .build();

                // Lambda 호출
                InvokeResponse invokeResponse = userLambdaClient.invoke(invokeRequest);

                // 응답 파싱
                String responseBody = invokeResponse.payload().asUtf8String();

                log.debug("CloudWatch Lambda response status code: {}", invokeResponse.statusCode());
                log.debug("CloudWatch Lambda response body: {}", responseBody);

                // JSON 응답을 DTO로 변환
                MonitorCloudWatchResponse response = objectMapper.readValue(
                    responseBody,
                    MonitorCloudWatchResponse.class
                );

                if (response.isSuccess()) {
                    log.info("CloudWatch metrics collected successfully - deploymentId: {}, " +
                        "blueLatency: {}ms, greenLatency: {}ms, blueErrorRate: {}, greenErrorRate: {}",
                        deploymentId,
                        response.getBlueLatencyMs(),
                        response.getGreenLatencyMs(),
                        response.getBlueErrorRate(),
                        response.getGreenErrorRate());
                } else {
                    log.warn("CloudWatch monitoring failed - deploymentId: {}, message: {}",
                        deploymentId, response.getMessage());
                }

                return response;

            } finally {
                // Lambda 클라이언트 정리
                userLambdaClient.close();
            }

        } catch (Exception e) {
            log.error("Failed to invoke CloudWatch monitoring Lambda for deploymentId: {}",
                deploymentId, e);
            throw new RuntimeException("Failed to invoke CloudWatch monitoring Lambda: " + e.getMessage(), e);
        }
    }

    /**
     * 사용자 AWS 계정 자격증명으로 Lambda 클라이언트 생성
     */
    private LambdaClient createUserLambdaClient(AwsConnection awsConnection) {
        try {
            Region region = Region.of(awsConnection.getRegion());

            // 세션 토큰이 있으면 AwsSessionCredentials, 없으면 AwsBasicCredentials 사용
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

            return LambdaClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();

        } catch (Exception e) {
            log.error("Failed to create Lambda client for user AWS account", e);
            throw new RuntimeException("Failed to create Lambda client: " + e.getMessage(), e);
        }
    }

    /**
     * 메트릭 응답을 SSE 이벤트 details로 변환
     */
    public java.util.Map<String, Object> convertResponseToEventDetails(
            MonitorCloudWatchResponse response) {
        return java.util.Map.ofEntries(
            java.util.Map.entry("stage", 5),
            java.util.Map.entry("blueLatencyMs", response.getBlueLatencyMs()),
            java.util.Map.entry("greenLatencyMs", response.getGreenLatencyMs()),
            java.util.Map.entry("blueErrorRate", response.getBlueErrorRate()),
            java.util.Map.entry("greenErrorRate", response.getGreenErrorRate()),
            java.util.Map.entry("blueCpuUtilization", response.getBlueCpuUtilization()),
            java.util.Map.entry("greenCpuUtilization", response.getGreenCpuUtilization()),
            java.util.Map.entry("blueMemoryUtilization", response.getBlueMemoryUtilization()),
            java.util.Map.entry("greenMemoryUtilization", response.getGreenMemoryUtilization()),
            java.util.Map.entry("blueRequestCount", response.getBlueRequestCount()),
            java.util.Map.entry("greenRequestCount", response.getGreenRequestCount())
        );
    }
}
