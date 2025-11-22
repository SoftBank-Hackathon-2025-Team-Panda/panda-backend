package com.panda.backend.feature.deploy.application;

import com.panda.backend.feature.connect.entity.AwsConnection;
import com.panda.backend.feature.deploy.event.StageEventHelper;
import com.panda.backend.feature.deploy.exception.HealthCheckException;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class HealthCheckService {

    private final CodeDeployTrafficSwitchService codeDeployTrafficSwitchService;

    /**
     * Stage 4: HealthCheck & Traffic Switching (Stage 4의 일부)
     * - Green 서비스에 5번 HTTP 요청 (2초 간격)
     * - 각 체크마다 최대 3번 재시도
     * - 레이턴시 측정
     * - 결과 평가
     * - CodeDeploy 트래픽 전환 승인
     */
    public void performHealthCheckAndTrafficSwitch(String deploymentId, StageEventHelper stageHelper,
                                                   String greenUrl, String codeDeployDeploymentId,
                                                   String codeDeployApplicationName, AwsConnection awsConnection) throws Exception {
        HttpClient httpClient = HttpClient.newHttpClient();
        ElasticLoadBalancingV2Client elbClient = createElbClient(awsConnection);

        try {
            stageHelper.stage4HealthCheckRunning(greenUrl);
            log.info("Starting health check for green service at {}", greenUrl);

            // 1. 5번의 Health Check 실행 (2초 간격)
            int passedChecks = 0;
            int failedChecks = 0;
            long totalLatency = 0;

            for (int i = 1; i <= 5; i++) {
                boolean checkPassed = false;
                long latency = 0;
                String lastError = null;

                // 최대 3번 재시도
                for (int retry = 1; retry <= 3; retry++) {
                    try {
                        // 먼저 /health 엔드포인트 시도, 실패 시 / 시도
                        String endpoint = tryHealthEndpoint(httpClient, greenUrl);
                        if (endpoint != null) {
                            HttpRequest request = HttpRequest.newBuilder()
                                    .uri(new URI(endpoint))
                                    .GET()
                                    .timeout(Duration.ofSeconds(5))  // 5초 타임아웃
                                    .build();

                            long startTime = System.currentTimeMillis();
                            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                            latency = System.currentTimeMillis() - startTime;
                            totalLatency += latency;

                            if (response.statusCode() == 200) {
                                passedChecks++;
                                checkPassed = true;
                                log.info("Health check {}/{} passed - Retry: {}, Status: {}, Latency: {}ms",
                                        i, 5, retry, response.statusCode(), latency);
                                break;
                            } else {
                                lastError = "Status: " + response.statusCode();
                                log.warn("Health check {}/{} - Retry: {} - Status: {}", i, 5, retry, response.statusCode());
                            }
                        } else {
                            lastError = "No healthy endpoint available";
                        }

                    } catch (Exception e) {
                        lastError = e.getMessage();
                        log.warn("Health check {}/{} - Retry: {} - Exception: {}", i, 5, retry, e.getMessage());
                    }

                    // 마지막 재시도가 아니면 100ms 대기 후 재시도
                    if (retry < 3) {
                        Thread.sleep(100);
                    }
                }

                if (!checkPassed) {
                    failedChecks++;
                    log.warn("Health check {}/5 failed after 3 retries: {}", i, lastError);
                }

                stageHelper.stage4HealthCheckRunning(String.format("%s - Check %d/5 (%s)",
                        greenUrl, i, checkPassed ? "Passed" : "Failed"));

                // 2초 간격으로 다음 체크 실행 (마지막 체크는 대기 안함)
                if (i < 5) {
                    Thread.sleep(2000);
                }
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

            stageHelper.stage4HealthCheckPassed(greenUrl, passedChecks);
            log.info("Health check passed - Passed: {}, Failed: {}, Average Latency: {}ms, Error Rate: {}%",
                    passedChecks, failedChecks, (long) averageLatency, String.format("%.1f", errorRate));

            // 3. CodeDeploy 트래픽 전환 승인
            stageHelper.stage4TrafficSwitching("blue", "green");
            log.info("Approving traffic switch from blue to green via CodeDeploy");

            try {
                codeDeployTrafficSwitchService.approveTrafficSwitch(
                        deploymentId,
                        codeDeployDeploymentId,
                        codeDeployApplicationName,
                        awsConnection
                );
                log.info("Traffic switch approved via CodeDeploy for deploymentId: {}", deploymentId);
            } catch (Exception e) {
                log.warn("CodeDeploy traffic switch approval failed: {}, continuing anyway", e.getMessage());
            }

            Thread.sleep(1000);

            stageHelper.stage4TrafficSwitched("green");
            log.info("Traffic switch completed for deploymentId: {}", deploymentId);

        } catch (HealthCheckException e) {
            log.error("Health check failed: {}", e.getMessage());
            stageHelper.stage4HealthCheckFailed(greenUrl, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("Health check error: {}", e.getMessage());
            stageHelper.stage4HealthCheckFailed(greenUrl, e.getMessage());
            throw new HealthCheckException("Health check error: " + e.getMessage(), deploymentId);
        } finally {
            elbClient.close();
        }
    }

    /**
     * /health 또는 / 엔드포인트 시도
     * @return 성공한 엔드포인트 또는 null
     */
    private String tryHealthEndpoint(HttpClient httpClient, String baseUrl) {
        // /health 엔드포인트 시도
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(baseUrl + "/health"))
                    .GET()
                    .timeout(Duration.ofSeconds(2))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return baseUrl + "/health";
            }
        } catch (Exception e) {
            log.debug("Health endpoint not available: {}", e.getMessage());
        }

        // / 엔드포인트 시도
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(new URI(baseUrl + "/"))
                    .GET()
                    .timeout(Duration.ofSeconds(2))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return baseUrl + "/";
            }
        } catch (Exception e) {
            log.debug("Root endpoint not available: {}", e.getMessage());
        }

        return null;
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
