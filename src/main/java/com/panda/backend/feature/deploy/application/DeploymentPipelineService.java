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
    private final EcsDeploymentService ecsDeploymentService;
    private final BlueGreenDeploymentService blueGreenDeploymentService;
    private final HealthCheckService healthCheckService;

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
            ecsDeploymentService.performEcsDeployment(deploymentId, stageHelper, ecrImageUri, awsConnection);

            // ====== Stage 4: CodeDeploy Blue/Green Lifecycle ======
            stageStartTime = checkTimeout(deploymentId, startTime, stageStartTime, 4);

            // ALB Target Group URL 동적 조회
            Map<String, String> urls = healthCheckService.getBlueGreenUrls(awsConnection);
            String blueUrl = urls.getOrDefault("blueUrl", "http://localhost:8080");
            String greenUrl = urls.getOrDefault("greenUrl", "http://localhost:8081");

            stageHelper.stage4Start(ecrImageUri);
            blueGreenDeploymentService.performBlueGreenDeployment(deploymentId, stageHelper, blueUrl, greenUrl, awsConnection);

            // ====== Stage 5: HealthCheck & Traffic Switching ======
            stageStartTime = checkTimeout(deploymentId, startTime, stageStartTime, 5);
            stageHelper.stage5Start(greenUrl);
            healthCheckService.performHealthCheckAndTrafficSwitch(deploymentId, stageHelper, greenUrl, awsConnection);

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
        String token = ghConnection.getToken();
        String gitUrl = String.format("https://%s:x-oauth-basic@github.com/%s/%s.git", token, owner, repo);
        String branchName = branch != null ? branch : "main";

        try {
            log.info("Cloning repository: {}/{} from branch: {}", owner, repo, branchName);

            // git clone --branch <branch> --depth 1 <url> <path>
            ProcessBuilder pb = new ProcessBuilder(
                    "git", "clone", "--branch", branchName, "--depth", "1", gitUrl, clonePath
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // stdout/stderr 로깅
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("Git clone output: {}", line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new RuntimeException("Failed to clone repository. Exit code: " + exitCode);
            }

            log.info("Repository cloned successfully to: {}", clonePath);
        } catch (Exception e) {
            log.error("Failed to clone repository: {}/{} - {}", owner, repo, e.getMessage(), e);
            throw new RuntimeException("Failed to clone repository: " + e.getMessage(), e);
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

}
