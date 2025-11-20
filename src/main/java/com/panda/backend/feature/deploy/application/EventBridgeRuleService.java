package com.panda.backend.feature.deploy.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.*;
import software.amazon.awssdk.services.iam.IamClient;
import software.amazon.awssdk.services.iam.model.*;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class EventBridgeRuleService {

    private static final String SOFTBANK_EVENT_BUS_ARN = "arn:aws:events:ap-northeast-2:919659241674:event-bus/softbank-event-bus";
    private static final String RULE_ROLE_NAME = "softbank-eventbridge-role";

    // TODO: AWS 파이프라인 팀에서 Step Functions State Machine ARN을 제공받아야 함
    // 환경 변수나 설정 파일에서 읽어오도록 수정 필요
    // private static final String STATE_MACHINE_ARN = "arn:aws:states:ap-northeast-2:919659241674:stateMachine:panda-deployment";

    private static final String RULE_ROLE_TRUST_POLICY = """
        {
          "Version": "2012-10-17",
          "Statement": [
            {
              "Effect": "Allow",
              "Principal": {
                "Service": "events.amazonaws.com"
              },
              "Action": "sts:AssumeRole"
            }
          ]
        }
        """;

    private static final String RULE_ROLE_POLICY = """
        {
          "Version": "2012-10-17",
          "Statement": [
            {
              "Effect": "Allow",
              "Action": "events:PutEvents",
              "Resource": "arn:aws:events:ap-northeast-2:919659241674:event-bus/softbank-event-bus"
            }
          ]
        }
        """;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 사용자 AWS 계정에 EventBridge 규칙 생성
     *
     * @param region AWS 리전
     * @param owner GitHub owner
     * @param repo GitHub repo name
     * @param accessKeyId AWS Access Key ID
     * @param secretAccessKey AWS Secret Access Key
     * @param sessionToken AWS Session Token (optional)
     */
    public void createEventBridgeRule(
            String region,
            String owner,
            String repo,
            String accessKeyId,
            String secretAccessKey,
            String sessionToken) {

        String ruleName = String.format("softbank-ecr-trigger-%s-%s", owner, repo).toLowerCase();
        String repositoryName = String.format("%s-%s", owner, repo).toLowerCase();

        EventBridgeClient eventBridgeClient = createEventBridgeClient(region, accessKeyId, secretAccessKey, sessionToken);
        IamClient iamClient = createIamClient(accessKeyId, secretAccessKey, sessionToken);

        try {
            // 1. IAM 역할 생성/확인
            String roleArn = ensureEventBridgeRole(iamClient);
            log.info("EventBridge role ensured: {}", roleArn);

            // 2. EventBridge 규칙 생성
            createRule(eventBridgeClient, ruleName, repositoryName, roleArn);
            log.info("EventBridge rule created: {}", ruleName);

        } catch (Exception e) {
            log.error("Failed to create EventBridge rule: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create EventBridge rule: " + e.getMessage(), e);
        } finally {
            eventBridgeClient.close();
            iamClient.close();
        }
    }

    /**
     * IAM 역할 확인 및 생성
     */
    private String ensureEventBridgeRole(IamClient iamClient) {
        try {
            // 역할이 이미 존재하는지 확인
            GetRoleResponse roleResponse = iamClient.getRole(GetRoleRequest.builder()
                    .roleName(RULE_ROLE_NAME)
                    .build());
            log.info("EventBridge role already exists: {}", roleResponse.role().arn());
            return roleResponse.role().arn();
        } catch (NoSuchEntityException e) {
            // 역할이 없으면 생성
            log.info("Creating EventBridge role: {}", RULE_ROLE_NAME);

            CreateRoleResponse roleResponse = iamClient.createRole(CreateRoleRequest.builder()
                    .roleName(RULE_ROLE_NAME)
                    .assumeRolePolicyDocument(RULE_ROLE_TRUST_POLICY)
                    .description("Role for EventBridge to put events to Softbank event bus")
                    .build());

            String roleArn = roleResponse.role().arn();
            log.info("EventBridge role created: {}", roleArn);

            // 인라인 정책 추가
            iamClient.putRolePolicy(PutRolePolicyRequest.builder()
                    .roleName(RULE_ROLE_NAME)
                    .policyName("softbank-eventbridge-policy")
                    .policyDocument(RULE_ROLE_POLICY)
                    .build());
            log.info("EventBridge role policy attached");

            // 역할 생성 직후 즉시 사용할 수 없을 수 있으므로 대기
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }

            return roleArn;
        }
    }

    /**
     * EventBridge 규칙 생성
     */
    private void createRule(
            EventBridgeClient client,
            String ruleName,
            String repositoryName,
            String roleArn) {

        try {
            // 규칙 존재 확인
            try {
                client.describeRule(DescribeRuleRequest.builder()
                        .name(ruleName)
                        .build());
                log.info("EventBridge rule already exists: {}", ruleName);
                return;
            } catch (ResourceNotFoundException e) {
                // 규칙이 없으면 생성
                log.info("Creating EventBridge rule: {}", ruleName);
            }

            // 이벤트 패턴 생성
            String eventPattern = createEventPattern(repositoryName);

            // 규칙 생성
            client.putRule(PutRuleRequest.builder()
                    .name(ruleName)
                    .description(String.format("Trigger Softbank deployment for %s", repositoryName))
                    .eventPattern(eventPattern)
                    .state(RuleState.ENABLED)
                    .build());

            log.info("EventBridge rule created successfully: {}", ruleName);

            // 대상(target) 추가
            client.putTargets(PutTargetsRequest.builder()
                    .rule(ruleName)
                    .targets(Target.builder()
                            .id("1")
                            .arn(SOFTBANK_EVENT_BUS_ARN)
                            .roleArn(roleArn)
                            .build())
                    .build());

            log.info("EventBridge target added: {}", SOFTBANK_EVENT_BUS_ARN);

        } catch (ResourceAlreadyExistsException e) {
            log.info("EventBridge rule already exists: {}", ruleName);
        }
    }

    /**
     * EventBridge 이벤트 패턴 생성
     *
     * {
     *   "source": ["aws.ecr"],
     *   "detail-type": ["ECR Image Action"],
     *   "detail": {
     *     "action-type": ["PUSH"],
     *     "result": ["SUCCESS"],
     *     "repository-name": ["사용자 ECR 레포 이름"],
     *     "manifest-media-type": [{
     *       "prefix": "application/vnd."
     *     }]
     *   }
     * }
     */
    private String createEventPattern(String repositoryName) {
        try {
            Map<String, Object> pattern = new HashMap<>();
            pattern.put("source", new String[]{"aws.ecr"});
            pattern.put("detail-type", new String[]{"ECR Image Action"});

            Map<String, Object> detail = new HashMap<>();
            detail.put("action-type", new String[]{"PUSH"});
            detail.put("result", new String[]{"SUCCESS"});
            detail.put("repository-name", new String[]{repositoryName});

            Map<String, Object> mediaType = new HashMap<>();
            mediaType.put("prefix", "application/vnd.");
            detail.put("manifest-media-type", new Object[]{mediaType});

            pattern.put("detail", detail);

            return objectMapper.writeValueAsString(pattern);
        } catch (Exception e) {
            log.error("Failed to create event pattern: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create event pattern", e);
        }
    }

    /**
     * EventBridge Client 생성
     */
    private EventBridgeClient createEventBridgeClient(
            String region,
            String accessKeyId,
            String secretAccessKey,
            String sessionToken) {

        if (sessionToken != null && !sessionToken.isEmpty()) {
            AwsSessionCredentials credentials = AwsSessionCredentials.create(
                    accessKeyId,
                    secretAccessKey,
                    sessionToken
            );
            return EventBridgeClient.builder()
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .build();
        } else {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(
                    accessKeyId,
                    secretAccessKey
            );
            return EventBridgeClient.builder()
                    .region(Region.of(region))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .build();
        }
    }

    /**
     * IAM Client 생성
     */
    private IamClient createIamClient(String accessKeyId, String secretAccessKey, String sessionToken) {
        if (sessionToken != null && !sessionToken.isEmpty()) {
            AwsSessionCredentials credentials = AwsSessionCredentials.create(
                    accessKeyId,
                    secretAccessKey,
                    sessionToken
            );
            return IamClient.builder()
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .build();
        } else {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(
                    accessKeyId,
                    secretAccessKey
            );
            return IamClient.builder()
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .build();
        }
    }
}
