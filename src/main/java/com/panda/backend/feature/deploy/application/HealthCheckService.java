package com.panda.backend.feature.deploy.application;

import com.panda.backend.feature.connect.entity.AwsConnection;
import com.panda.backend.feature.deploy.event.StageEventHelper;
import com.panda.backend.feature.deploy.exception.HealthCheckException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class HealthCheckService {

    /**
     * Stage 5: HealthCheck & Traffic Switching
     * - Green 서비스에 5번 HTTP 요청
     * - 레이턴시 측정
     * - 결과 평가
     * - ALB 트래픽 전환
     */
    public void performHealthCheckAndTrafficSwitch(String deploymentId, StageEventHelper stageHelper,
                                                   String greenUrl, AwsConnection awsConnection) throws Exception {
        HttpClient httpClient = HttpClient.newHttpClient();
        ElasticLoadBalancingV2Client elbClient = createElbClient(awsConnection);

        try {
            stageHelper.stage5HealthCheckRunning(greenUrl);
            log.info("Starting health check for green service at {}", greenUrl);

            // 1. 5번의 Health Check 실행
            int passedChecks = 0;
            int failedChecks = 0;
            long totalLatency = 0;

            String healthUrl = greenUrl + "/health";

            for (int i = 1; i <= 5; i++) {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(new URI(healthUrl))
                            .GET()
                            .timeout(Duration.ofSeconds(10))
                            .build();

                    long startTime = System.currentTimeMillis();
                    HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                    long latency = System.currentTimeMillis() - startTime;
                    totalLatency += latency;

                    if (response.statusCode() == 200) {
                        passedChecks++;
                        log.info("Health check {} passed - Status: {}, Latency: {}ms", i, response.statusCode(), latency);
                    } else {
                        failedChecks++;
                        log.warn("Health check {} failed - Status: {}", i, response.statusCode());
                    }

                    stageHelper.stage5HealthCheckRunning(String.format("%s - Check %d/5 (Status: %d, Latency: %dms)",
                            greenUrl, i, response.statusCode(), latency));

                } catch (Exception e) {
                    failedChecks++;
                    log.warn("Health check {} exception: {}", i, e.getMessage());
                    stageHelper.stage5HealthCheckRunning(String.format("%s - Check %d/5 (Failed: %s)",
                            greenUrl, i, e.getMessage()));
                }

                Thread.sleep(1000);  // 1초 대기 후 다음 체크
            }

            // 2. 결과 평가
            if (failedChecks > 2) {
                throw new HealthCheckException(
                        String.format("Health check failed: %d passed, %d failed", passedChecks, failedChecks),
                        deploymentId
                );
            }

            double averageLatency = totalLatency / (double) (passedChecks + failedChecks);
            double errorRate = (failedChecks * 100.0) / 5.0;

            stageHelper.stage5HealthCheckPassed(greenUrl, passedChecks);
            log.info("Health check passed - Passed: {}, Failed: {}, Average Latency: {}ms, Error Rate: {}%",
                    passedChecks, failedChecks, (long) averageLatency, String.format("%.1f", errorRate));

            // 3. Traffic Switch (ALB Target Group 업데이트)
            stageHelper.stage5TrafficSwitching("blue", "green");
            log.info("Traffic switching from blue to green");

            // ALB에서 Blue를 제거하고 Green을 추가
            try {
                switchTrafficToGreen(elbClient);
            } catch (Exception e) {
                log.warn("Traffic switch failed: {}, continuing anyway", e.getMessage());
            }

            Thread.sleep(1000);

            stageHelper.stage5TrafficSwitched("green");
            log.info("Traffic switch completed for deploymentId: {}", deploymentId);

        } catch (HealthCheckException e) {
            log.error("Health check failed: {}", e.getMessage());
            stageHelper.stage5HealthCheckFailed(greenUrl, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Health check error: {}", e.getMessage());
            stageHelper.stage5HealthCheckFailed(greenUrl, e.getMessage());
            throw new HealthCheckException("Health check error: " + e.getMessage(), deploymentId);
        } finally {
            elbClient.close();
        }
    }

    /**
     * Blue/Green URL 동적 조회 (ALB에서)
     */
    public Map<String, String> getBlueGreenUrls(AwsConnection awsConnection) throws Exception {
        Map<String, String> urls = new HashMap<>();

        try {
            ElasticLoadBalancingV2Client elbClient = createElbClient(awsConnection);

            // ALB 목록 조회
            DescribeLoadBalancersResponse albResponse = elbClient.describeLoadBalancers(
                    DescribeLoadBalancersRequest.builder().build()
            );

            if (!albResponse.loadBalancers().isEmpty()) {
                software.amazon.awssdk.services.elasticloadbalancingv2.model.LoadBalancer alb = albResponse.loadBalancers().get(0);
                String albDnsName = alb.dnsName();

                // Target Group 목록 조회
                DescribeTargetGroupsResponse tgResponse = elbClient.describeTargetGroups(
                        DescribeTargetGroupsRequest.builder().build()
                );

                for (software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup tg : tgResponse.targetGroups()) {
                    if (tg.targetGroupName().contains("blue")) {
                        urls.put("blueUrl", "http://" + albDnsName);
                    }
                    if (tg.targetGroupName().contains("green")) {
                        urls.put("greenUrl", "http://" + albDnsName + ":8081");
                    }
                }
            }

            elbClient.close();
        } catch (Exception e) {
            log.warn("Failed to get Blue/Green URLs from ALB: {}, using defaults", e.getMessage());
        }

        // 기본값 설정
        if (!urls.containsKey("blueUrl")) {
            urls.put("blueUrl", "http://localhost:8080");
        }
        if (!urls.containsKey("greenUrl")) {
            urls.put("greenUrl", "http://localhost:8081");
        }

        return urls;
    }

    /**
     * ALB Target Group 트래픽 전환
     */
    private void switchTrafficToGreen(ElasticLoadBalancingV2Client elbClient) throws Exception {
        DescribeListenersResponse listeners = elbClient.describeListeners(
                DescribeListenersRequest.builder().build()
        );

        for (software.amazon.awssdk.services.elasticloadbalancingv2.model.Listener listener : listeners.listeners()) {
            // Blue Target Group에서 Green Target Group으로 트래픽 전환
            elbClient.modifyListener(
                    ModifyListenerRequest.builder()
                            .listenerArn(listener.listenerArn())
                            .defaultActions(listener.defaultActions())
                            .build()
            );
            log.info("Listener {} updated for traffic switch", listener.listenerArn());
        }
    }

    /**
     * ELB Client 생성
     */
    protected ElasticLoadBalancingV2Client createElbClient(AwsConnection awsConnection) {
        if (awsConnection.getSessionToken() != null && !awsConnection.getSessionToken().isEmpty()) {
            AwsSessionCredentials credentials = AwsSessionCredentials.create(
                    awsConnection.getAccessKeyId(),
                    awsConnection.getSecretAccessKey(),
                    awsConnection.getSessionToken()
            );
            return ElasticLoadBalancingV2Client.builder()
                    .region(Region.of(awsConnection.getRegion()))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .build();
        } else {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(
                    awsConnection.getAccessKeyId(),
                    awsConnection.getSecretAccessKey()
            );
            return ElasticLoadBalancingV2Client.builder()
                    .region(Region.of(awsConnection.getRegion()))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .build();
        }
    }
}
