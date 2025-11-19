package com.panda.backend.feature.deploy.application;

import com.panda.backend.feature.connect.entity.AwsConnection;
import com.panda.backend.feature.connect.entity.GitHubConnection;
import com.panda.backend.feature.deploy.event.DeploymentEventPublisher;
import com.panda.backend.feature.deploy.event.StageEventHelper;
import com.panda.backend.feature.deploy.exception.*;
import com.panda.backend.feature.deploy.infrastructure.DeploymentErrorHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ecr.EcrClient;
import software.amazon.awssdk.services.ecr.model.*;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.GetCallerIdentityRequest;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeploymentPipelineService {

    private static final String TEMP_DIR = System.getProperty("java.io.tmpdir");
    private static final long DEPLOYMENT_TIMEOUT_SECONDS = 30 * 60;  // 30분
    private static final long STAGE_TIMEOUT_SECONDS = 10 * 60;  // 10분

    private final DeploymentEventPublisher eventPublisher;
    private final DeploymentErrorHandler errorHandler;

    public void triggerDeploymentPipeline(String deploymentId, GitHubConnection ghConnection, AwsConnection awsConnection,
                                         String owner, String repo, String branch) {
        StageEventHelper stageHelper = new StageEventHelper(deploymentId, eventPublisher);
        long startTime = System.currentTimeMillis();
        long stageStartTime = startTime;

        try {
            // ====== Stage 1: Dockerfile 탐색 + Docker Build ======
            stageStartTime = checkTimeout(deploymentId, startTime, stageStartTime, 1);
            stageHelper.stage1Start();

            String cloneDir = cloneRepository(deploymentId, ghConnection, owner, repo, branch);
            stageHelper.stage1RepositoryCloned(cloneDir);

            stageHelper.stage1DockerfileSearching();
            String dockerfilePath = findDockerfile(cloneDir);
            if (dockerfilePath == null) {
                throw new DeploymentException("Dockerfile not found in repository", deploymentId, 1);
            }
            stageHelper.stage1DockerfileFound(dockerfilePath);

            stageHelper.stage1BuildStarting();
            String imageName = buildDockerImage(deploymentId, cloneDir, owner, repo, branch);
            stageHelper.stage1BuildCompleted(imageName);

            // ====== Stage 2: ECR Push ======
            stageStartTime = checkTimeout(deploymentId, startTime, stageStartTime, 2);
            stageHelper.stage2Start();

            String repositoryName = String.format("%s-%s", owner, repo).toLowerCase();
            stageHelper.stage2RepositoryEnsured(repositoryName);

            stageHelper.stage2LoginStarting();
            String ecrImageUri = pushToEcr(deploymentId, imageName, awsConnection, owner, repo);
            stageHelper.stage2LoginCompleted();

            stageHelper.stage2PushStarting(ecrImageUri);
            stageHelper.stage2PushCompleted(ecrImageUri);

            // ====== Stage 3: ECS Deployment 시작 ======
            stageStartTime = checkTimeout(deploymentId, startTime, stageStartTime, 3);
            stageHelper.stage3Start(ecrImageUri);
            performEcsDeployment(deploymentId, stageHelper, ecrImageUri);

            // ====== Stage 4: CodeDeploy Blue/Green Lifecycle ======
            stageStartTime = checkTimeout(deploymentId, startTime, stageStartTime, 4);
            // TODO: Blue/Green URL을 AWS API에서 동적으로 조회
            // 현재: 하드코딩된 URL 사용 (시뮬레이션용)
            // 구현 필요:
            // 1. ELBv2Client로 Target Group 조회
            // 2. ALB DNS 주소 또는 도메인명 조회
            // 3. Blue/Green Target Group 구분
            // 4. 실제 서비스 URL 획득
            // 참고: DeploymentMetadata에 저장하여 결과 조회 시 반환
            String blueUrl = "http://blue.example.com";
            String greenUrl = "http://green.example.com";
            stageHelper.stage4Start(ecrImageUri);
            performBlueGreenDeployment(deploymentId, stageHelper, blueUrl, greenUrl);

            // ====== Stage 5: HealthCheck & Traffic Switching ======
            stageStartTime = checkTimeout(deploymentId, startTime, stageStartTime, 5);
            stageHelper.stage5Start(greenUrl);
            performHealthCheckAndTrafficSwitch(deploymentId, stageHelper, greenUrl);

            // ====== Stage 6: 완료 ======
            checkTimeout(deploymentId, startTime, stageStartTime, 6);
            stageHelper.stage6Complete("green", blueUrl, greenUrl);
            eventPublisher.publishSuccessEvent(deploymentId, "green", blueUrl, greenUrl);

        } catch (DeploymentTimeoutException e) {
            log.error("Deployment timeout at stage {}: {}", e.getStage(), e.getMessage());
            errorHandler.handleException(deploymentId, e);
        } catch (DockerBuildException e) {
            log.error("Docker build failed: {}", e.getMessage());
            errorHandler.handleException(deploymentId, e);
        } catch (HealthCheckException e) {
            log.error("Health check failed: {}", e.getMessage());
            errorHandler.handleException(deploymentId, e);
        } catch (DeploymentException e) {
            log.error("Deployment failed at stage {}: {}", e.getStage(), e.getMessage());
            errorHandler.handleException(deploymentId, e);
        } catch (Exception e) {
            log.error("Unexpected error during deployment pipeline", e);
            errorHandler.handleException(deploymentId, e);
        }
    }

    // 타임아웃 체크 (각 단계 시작 시)
    private long checkTimeout(String deploymentId, long deploymentStartTime, long stageStartTime, Integer currentStage) {
        long totalDuration = (System.currentTimeMillis() - deploymentStartTime) / 1000;
        long stageDuration = (System.currentTimeMillis() - stageStartTime) / 1000;

        // 전체 배포 타임아웃 체크
        if (totalDuration > DEPLOYMENT_TIMEOUT_SECONDS) {
            throw new DeploymentTimeoutException(
                    "Overall deployment timeout exceeded",
                    deploymentId,
                    currentStage,
                    totalDuration,
                    DEPLOYMENT_TIMEOUT_SECONDS
            );
        }

        // 단계별 타임아웃 체크 (이전 단계)
        if (stageDuration > STAGE_TIMEOUT_SECONDS) {
            throw new DeploymentTimeoutException(
                    "Stage timeout exceeded",
                    deploymentId,
                    currentStage - 1,
                    stageDuration,
                    STAGE_TIMEOUT_SECONDS
            );
        }

        log.debug("Stage {} timeout check passed - total: {}s, stage: {}s",
                currentStage, totalDuration, stageDuration);

        return System.currentTimeMillis();
    }

    private String cloneRepository(String deploymentId, GitHubConnection ghConnection, String owner, String repo, String branch) throws Exception {
        String clonePath = Paths.get(TEMP_DIR, "deployment_" + System.currentTimeMillis()).toString();
        Files.createDirectories(Paths.get(clonePath));

        // TODO: GitHub Token 보안 개선
        // 현재: Token을 Git URL에 포함 (평문 저장 위험)
        // 개선 방안:
        // 1. Git Credential Helper 사용
        // 2. SSH 키 기반 인증으로 변경
        // 3. GitHub Apps 사용
        // 또한, 클론 완료 후 임시 디렉토리 정리 필요:
        // - 배포 완료 후 /tmp/deployment_* 디렉토리 삭제
        // - git URL에 포함된 token이 파일에 남아있지 않도록 주의
        String gitUrl = String.format("https://%s@github.com/%s/%s.git", ghConnection.getToken(), owner, repo);
        String branchName = branch != null ? branch : "main";

        ProcessBuilder pb = new ProcessBuilder(
                "git", "clone", "--depth", "1", "-b", branchName, gitUrl, clonePath
        );
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("Git clone: {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Failed to clone repository. Exit code: " + exitCode);
        }

        return clonePath;
    }

    private String findDockerfile(String repoPath) {
        Path searchPath = Paths.get(repoPath);
        try {
            return Files.walk(searchPath)
                    .filter(p -> p.getFileName().toString().equals("Dockerfile"))
                    .map(Path::toString)
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.warn("Error searching for Dockerfile", e);
            return null;
        }
    }

    private String buildDockerImage(String deploymentId, String repoPath, String owner, String repo, String branch) throws Exception {
        String branchName = branch != null ? branch : "main";
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String imageName = String.format("%s-%s-%s-%s", owner, repo, branchName, timestamp).toLowerCase();

        ProcessBuilder pb = new ProcessBuilder("docker", "build", "-t", imageName, ".");
        pb.directory(new File(repoPath));
        pb.redirectErrorStream(true);

        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("Docker build: {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Docker build failed. Exit code: " + exitCode);
        }

        return imageName;
    }

    private String pushToEcr(String deploymentId, String localImageName, AwsConnection awsConnection, String owner, String repo) throws Exception {
        // Get AWS account ID and ECR registry URL
        String accountId = getAwsAccountId(awsConnection);
        String region = awsConnection.getRegion();
        String registryUrl = String.format("%s.dkr.ecr.%s.amazonaws.com", accountId, region);

        // Create ECR repository if not exists
        String repositoryName = String.format("%s-%s", owner, repo).toLowerCase();
        ensureEcrRepository(awsConnection, repositoryName);

        // Docker login to ECR
        loginToEcr(awsConnection, registryUrl);

        // Tag and push image
        String ecrImageUri = String.format("%s/%s:%s", registryUrl, repositoryName, localImageName);
        ProcessBuilder tagPb = new ProcessBuilder("docker", "tag", localImageName, ecrImageUri);
        tagPb.redirectErrorStream(true);
        Process tagProcess = tagPb.start();
        tagProcess.waitFor();

        ProcessBuilder pushPb = new ProcessBuilder("docker", "push", ecrImageUri);
        pushPb.redirectErrorStream(true);
        Process pushProcess = pushPb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(pushProcess.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("Docker push: {}", line);
            }
        }

        int exitCode = pushProcess.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Docker push to ECR failed. Exit code: " + exitCode);
        }

        return ecrImageUri;
    }

    private String getAwsAccountId(AwsConnection awsConnection) throws Exception {
        StsClient stsClient = createStsClient(awsConnection);
        try {
            return stsClient.getCallerIdentity(GetCallerIdentityRequest.builder().build()).account();
        } finally {
            stsClient.close();
        }
    }

    private void ensureEcrRepository(AwsConnection awsConnection, String repositoryName) throws Exception {
        EcrClient ecrClient = createEcrClient(awsConnection);
        try {
            try {
                ecrClient.describeRepositories(DescribeRepositoriesRequest.builder()
                        .repositoryNames(repositoryName)
                        .build());
                log.info("ECR repository {} already exists", repositoryName);
            } catch (RepositoryNotFoundException e) {
                ecrClient.createRepository(CreateRepositoryRequest.builder()
                        .repositoryName(repositoryName)
                        .build());
                log.info("ECR repository {} created", repositoryName);
            }
        } finally {
            ecrClient.close();
        }
    }

    private void loginToEcr(AwsConnection awsConnection, String registryUrl) throws Exception {
        EcrClient ecrClient = createEcrClient(awsConnection);
        try {
            GetAuthorizationTokenResponse authToken = ecrClient.getAuthorizationToken(GetAuthorizationTokenRequest.builder().build());
            String token = authToken.authorizationData().get(0).authorizationToken();

            // Decode token and extract password
            String decodedToken = new String(java.util.Base64.getDecoder().decode(token));
            String password = decodedToken.split(":")[1];

            ProcessBuilder pb = new ProcessBuilder(
                    "docker", "login", "-u", "AWS", "-p", password, registryUrl
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();

            if (exitCode != 0) {
                throw new RuntimeException("Docker ECR login failed. Exit code: " + exitCode);
            }
        } finally {
            ecrClient.close();
        }
    }

    private StsClient createStsClient(AwsConnection awsConnection) {
        if (awsConnection.getSessionToken() != null && !awsConnection.getSessionToken().isEmpty()) {
            AwsSessionCredentials credentials = AwsSessionCredentials.create(
                    awsConnection.getAccessKeyId(),
                    awsConnection.getSecretAccessKey(),
                    awsConnection.getSessionToken()
            );
            return StsClient.builder()
                    .region(Region.of(awsConnection.getRegion()))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .build();
        } else {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(
                    awsConnection.getAccessKeyId(),
                    awsConnection.getSecretAccessKey()
            );
            return StsClient.builder()
                    .region(Region.of(awsConnection.getRegion()))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .build();
        }
    }

    private EcrClient createEcrClient(AwsConnection awsConnection) {
        if (awsConnection.getSessionToken() != null && !awsConnection.getSessionToken().isEmpty()) {
            AwsSessionCredentials credentials = AwsSessionCredentials.create(
                    awsConnection.getAccessKeyId(),
                    awsConnection.getSecretAccessKey(),
                    awsConnection.getSessionToken()
            );
            return EcrClient.builder()
                    .region(Region.of(awsConnection.getRegion()))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .build();
        } else {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(
                    awsConnection.getAccessKeyId(),
                    awsConnection.getSecretAccessKey()
            );
            return EcrClient.builder()
                    .region(Region.of(awsConnection.getRegion()))
                    .credentialsProvider(StaticCredentialsProvider.create(credentials))
                    .build();
        }
    }

    // ====== Stage 3: ECS Deployment ======
    private void performEcsDeployment(String deploymentId, StageEventHelper stageHelper, String ecrImageUri) throws Exception {
        try {
            // TODO: Stage 3 구현 - ECS 실제 배포
            // 1. EcsClient 생성 (AWS credentials 사용)
            // 2. ECS Cluster 확인/생성 (DescribeCluster, CreateCluster)
            // 3. TaskDefinition 등록 (RegisterTaskDefinition with ecrImageUri)
            // 4. Service 생성/업데이트 (CreateService, UpdateService)
            // 5. 배포 상태 Polling (DescribeServices until status == ACTIVE)
            // 6. 각 단계에서 stageHelper 이벤트 발행
            // 현재: 시뮬레이션만 구현됨
            Thread.sleep(500);  // Simulate ECS operations
            stageHelper.stage3ServiceCreated("panda-service", "panda-cluster");
            Thread.sleep(500);
            stageHelper.stage3ServiceUpdated("panda-service");
            log.info("ECS deployment completed for deploymentId: {}", deploymentId);
        } catch (InterruptedException e) {
            log.warn("ECS deployment interrupted for deploymentId: {}", deploymentId);
            Thread.currentThread().interrupt();
            throw new RuntimeException("ECS deployment interrupted");
        }
    }

    // ====== Stage 4: CodeDeploy Blue/Green Lifecycle ======
    private void performBlueGreenDeployment(String deploymentId, StageEventHelper stageHelper,
                                            String blueUrl, String greenUrl) throws Exception {
        try {
            // TODO: Stage 4 구현 - CodeDeploy Blue/Green 배포
            // 1. CodeDeployClient 생성
            // 2. Blue Service 상태 확인 (ECS DescribeServices)
            // 3. Green Service 생성 (새로운 ECS Task)
            // 4. CodeDeploy Deployment 생성 (CreateDeployment)
            // 5. Lifecycle Events Polling (GetDeployment)
            //    - BeforeAllowTraffic: 유효성 검사 실행
            //    - AfterAllowTraffic: 트래픽 전환 준비
            // 6. Deployment 상태 대기 (SUCCESS or FAILURE)
            // 7. 각 단계에서 stageHelper 이벤트 발행
            // 현재: 시뮬레이션만 구현됨
            stageHelper.stage4BlueServiceRunning(blueUrl);
            Thread.sleep(500);

            stageHelper.stage4GreenServiceSpinning(greenUrl);
            Thread.sleep(1000);  // Simulate Green service startup

            stageHelper.stage4GreenServiceReady(greenUrl);
            Thread.sleep(500);

            // Simulate CodeDeploy lifecycle hooks
            stageHelper.stage4LifecycleHook("BeforeAllowTraffic");
            Thread.sleep(300);

            stageHelper.stage4LifecycleHook("AfterAllowTraffic");
            Thread.sleep(300);

            log.info("Blue/Green deployment completed for deploymentId: {}", deploymentId);
        } catch (InterruptedException e) {
            log.warn("Blue/Green deployment interrupted for deploymentId: {}", deploymentId);
            Thread.currentThread().interrupt();
            throw new RuntimeException("Blue/Green deployment interrupted");
        }
    }

    // ====== Stage 5: HealthCheck & Traffic Switching ======
    private void performHealthCheckAndTrafficSwitch(String deploymentId, StageEventHelper stageHelper,
                                                    String greenUrl) throws Exception {
        try {
            // TODO: Stage 5 구현 - 실제 헬스체크 및 트래픽 전환
            // 1. ElasticLoadBalancingV2Client 생성
            // 2. 5번 반복 (greenUrl + "/health" 요청):
            //    - HTTP GET 요청 실행
            //    - 응답 시간(Latency) 측정
            //    - Status Code 확인
            //    - stageHelper 이벤트 발행
            // 3. 결과 집계:
            //    - 성공 횟수 / 실패 횟수
            //    - 평균 레이턴시 계산
            //    - 에러율 계산
            // 4. 조건 체크:
            //    - 성공 > 3회? → Traffic Switch 진행
            //    - 실패 > 2회? → HealthCheckException 발생
            // 5. ALB Target Group 업데이트 (Blue 제거, Green 추가)
            // 6. Traffic Switch 완료 후 메트릭 저장
            // 현재: 시뮬레이션만 구현됨
            stageHelper.stage5HealthCheckRunning(greenUrl);
            Thread.sleep(500);

            // Simulate health checks
            int passedChecks = 0;
            for (int i = 1; i <= 5; i++) {
                Thread.sleep(200);
                stageHelper.stage5HealthCheckRunning(greenUrl + " - Check " + i + "/5");
                passedChecks++;
            }

            stageHelper.stage5HealthCheckPassed(greenUrl, passedChecks);
            Thread.sleep(300);

            stageHelper.stage5TrafficSwitching("blue", "green");
            Thread.sleep(500);

            stageHelper.stage5TrafficSwitched("green");
            log.info("Health check and traffic switch completed for deploymentId: {}", deploymentId);
        } catch (InterruptedException e) {
            log.warn("Health check interrupted for deploymentId: {}", deploymentId);
            stageHelper.stage5HealthCheckFailed(greenUrl, "Process interrupted");
            Thread.currentThread().interrupt();
            throw new RuntimeException("Health check interrupted");
        }
    }
}
