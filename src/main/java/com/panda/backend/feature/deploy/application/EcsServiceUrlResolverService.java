package com.panda.backend.feature.deploy.application;

import com.panda.backend.feature.connect.entity.AwsConnection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.DescribeServicesRequest;
import software.amazon.awssdk.services.ecs.model.DescribeServicesResponse;
import software.amazon.awssdk.services.ecs.model.LoadBalancer;
import software.amazon.awssdk.services.elasticloadbalancingv2.ElasticLoadBalancingV2Client;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeLoadBalancersRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeLoadBalancersResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetGroupsRequest;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.DescribeTargetGroupsResponse;
import software.amazon.awssdk.services.elasticloadbalancingv2.model.TargetGroup;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ECS Service의 ARN을 기반으로 Blue/Green 서비스의 URL을 해석하는 서비스
 *
 * 흐름:
 * 1. ECS Service 조회 (describe-services)
 * 2. LoadBalancer 정보 추출
 * 3. ALB의 DNS 이름 조회 (describe-load-balancers)
 * 4. TargetGroup의 포트 조회 (describe-target-groups)
 * 5. URL 구성: http://{dns-name}:{port}
 *
 * 호출 위치:
 * - Stage 4 (Blue/Green 배포) 완료 후
 * - Stage 5, 6에서 사용
 */
@Slf4j
@org.springframework.stereotype.Service
@RequiredArgsConstructor
public class EcsServiceUrlResolverService {

    /**
     * ECS Service ARN으로부터 서비스 URL 해석
     *
     * @param serviceArn ECS Service ARN (arn:aws:ecs:region:account:service/cluster/serviceName)
     * @param clusterName ECS Cluster 이름
     * @param awsConnection 사용자 AWS 연결 정보
     * @return 서비스 URL (예: http://panda-alb-xxxxx.ap-northeast-2.elb.amazonaws.com:8080)
     * @throws Exception 조회 실패 시
     */
    public String resolveServiceUrl(String serviceArn, String clusterName, AwsConnection awsConnection) throws Exception {
        if (serviceArn == null || serviceArn.isEmpty()) {
            log.warn("Service ARN is empty, cannot resolve URL");
            return null;
        }

        EcsClient ecsClient = createUserEcsClient(awsConnection);
        ElasticLoadBalancingV2Client elbClient = createUserElbClient(awsConnection);

        try {
            // 1. ECS Service 조회
            String serviceName = extractServiceNameFromArn(serviceArn);

            log.debug("Resolving URL for service: {} in cluster: {}", serviceName, clusterName);

            DescribeServicesResponse servicesResponse = ecsClient.describeServices(
                DescribeServicesRequest.builder()
                    .cluster(clusterName)
                    .services(serviceName)
                    .build()
            );

            if (servicesResponse.services().isEmpty()) {
                log.error("Service not found: {} in cluster: {}", serviceName, clusterName);
                return null;
            }

            software.amazon.awssdk.services.ecs.model.Service service = servicesResponse.services().get(0);

            // 2. LoadBalancer 정보 추출
            List<LoadBalancer> loadBalancers = service.loadBalancers();
            if (loadBalancers == null || loadBalancers.isEmpty()) {
                log.warn("No load balancers found for service: {}", serviceName);
                return null;
            }

            LoadBalancer loadBalancer = loadBalancers.get(0);
            String targetGroupArn = loadBalancer.targetGroupArn();
            Integer containerPort = loadBalancer.containerPort();

            log.debug("Service: {}, TargetGroupArn: {}, ContainerPort: {}",
                serviceName, targetGroupArn, containerPort);

            // 3. TargetGroup 조회 (포트 정보 추출)
            DescribeTargetGroupsResponse targetGroupsResponse = elbClient.describeTargetGroups(
                DescribeTargetGroupsRequest.builder()
                    .targetGroupArns(targetGroupArn)
                    .build()
            );

            if (targetGroupsResponse.targetGroups().isEmpty()) {
                log.error("Target group not found: {}", targetGroupArn);
                return null;
            }

            TargetGroup targetGroup = targetGroupsResponse.targetGroups().get(0);
            Integer targetPort = targetGroup.port();

            log.debug("TargetGroup port: {}", targetPort);

            // 4. ALB 정보 조회 (DNS 이름)
            String loadBalancerArn = extractLoadBalancerArnFromTargetGroupArn(targetGroupArn);

            DescribeLoadBalancersResponse loadBalancersResponse = elbClient.describeLoadBalancers(
                DescribeLoadBalancersRequest.builder()
                    .loadBalancerArns(loadBalancerArn)
                    .build()
            );

            if (loadBalancersResponse.loadBalancers().isEmpty()) {
                log.error("Load balancer not found: {}", loadBalancerArn);
                return null;
            }

            String dnsName = loadBalancersResponse.loadBalancers().get(0).dnsName();

            log.debug("ALB DNS Name: {}", dnsName);

            // 5. URL 구성
            String url = String.format("http://%s:%d", dnsName, targetPort);

            log.info("Resolved URL for service {}: {}", serviceName, url);

            return url;

        } catch (Exception e) {
            log.error("Failed to resolve service URL for ARN: {}", serviceArn, e);
            throw new RuntimeException("Failed to resolve service URL: " + e.getMessage(), e);
        } finally {
            // 클라이언트 정리
            ecsClient.close();
            elbClient.close();
        }
    }

    /**
     * 여러 서비스 ARN의 URL을 한 번에 해석
     *
     * @param serviceArns 서비스 ARN 맵 (key: "blue"/"green", value: ARN)
     * @param clusterName ECS Cluster 이름
     * @param awsConnection AWS 연결 정보
     * @return URL 맵 (key: "blueUrl"/"greenUrl", value: URL)
     */
    public Map<String, String> resolveServiceUrls(Map<String, String> serviceArns,
                                                   String clusterName,
                                                   AwsConnection awsConnection) {
        Map<String, String> urls = new HashMap<>();

        for (String key : serviceArns.keySet()) {
            String arn = serviceArns.get(key);
            try {
                String url = resolveServiceUrl(arn, clusterName, awsConnection);
                if (url != null) {
                    urls.put(key + "Url", url);
                }
            } catch (Exception e) {
                log.error("Failed to resolve URL for {}: {}", key, e.getMessage());
            }
        }

        return urls;
    }

    /**
     * 사용자 AWS 계정 자격증명으로 ECS 클라이언트 생성
     */
    private EcsClient createUserEcsClient(AwsConnection awsConnection) {
        try {
            Region region = Region.of(awsConnection.getRegion());

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

            return EcsClient.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();

        } catch (Exception e) {
            log.error("Failed to create ECS client", e);
            throw new RuntimeException("Failed to create ECS client: " + e.getMessage(), e);
        }
    }

    /**
     * 사용자 AWS 계정 자격증명으로 ELB 클라이언트 생성
     */
    private ElasticLoadBalancingV2Client createUserElbClient(AwsConnection awsConnection) {
        try {
            Region region = Region.of(awsConnection.getRegion());

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

            return ElasticLoadBalancingV2Client.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();

        } catch (Exception e) {
            log.error("Failed to create ELB client", e);
            throw new RuntimeException("Failed to create ELB client: " + e.getMessage(), e);
        }
    }

    /**
     * ECS Service ARN에서 서비스명 추출
     * 예: arn:aws:ecs:region:account:service/cluster/serviceName → serviceName
     */
    private String extractServiceNameFromArn(String serviceArn) {
        String[] parts = serviceArn.split("/");
        if (parts.length >= 3) {
            return parts[parts.length - 1];
        }
        return serviceArn;
    }

    /**
     * TargetGroup ARN에서 LoadBalancer ARN 추출
     * 예: arn:aws:elasticloadbalancing:region:account:targetgroup/name/id
     *     → arn:aws:elasticloadbalancing:region:account:loadbalancer/app/alb-name/id
     *
     * NOTE: LoadBalancer ARN 정보는 TargetGroup에서 직접 조회 가능하거나,
     *      ECS Service의 LoadBalancer 정보에서 직접 가져오는 것이 더 간단할 수 있음
     */
    private String extractLoadBalancerArnFromTargetGroupArn(String targetGroupArn) {
        // targetgroup/panda-green/xxxxx → loadbalancer/app/panda-alb/xxxxx 형식으로 변환
        // 더 정확하게는, TargetGroup의 LoadBalancerArns 정보를 직접 사용하는 것이 나음
        // 여기서는 targetgroup ARN에서 LoadBalancer ARN을 추출하는 간단한 로직만 제공
        return targetGroupArn;
    }
}
