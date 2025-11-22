package com.panda.backend.feature.deploy.api;

import com.panda.backend.feature.deploy.application.GetDeploymentResultService;
import com.panda.backend.feature.deploy.application.LambdaInvocationService;
import com.panda.backend.feature.deploy.application.StartDeploymentService;
import com.panda.backend.feature.deploy.application.StreamDeploymentEventsService;
import com.panda.backend.feature.deploy.dto.ApproveDeploymentRequest;
import com.panda.backend.feature.deploy.dto.ApproveDeploymentResponse;
import com.panda.backend.feature.deploy.dto.DeployRequest;
import com.panda.backend.feature.deploy.dto.DeployResponse;
import com.panda.backend.feature.deploy.dto.DeploymentResult;
import com.panda.backend.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
public class DeployController implements DeployApi {

    private final StartDeploymentService startDeploymentService;
    private final GetDeploymentResultService getDeploymentResultService;
    private final StreamDeploymentEventsService streamDeploymentEventsService;
    private final LambdaInvocationService lambdaInvocationService;

    @Override
    @PostMapping("/api/v1/deploy")
    public ApiResponse<DeployResponse> deploy(@RequestBody DeployRequest request) {
        try {
            DeployResponse response = startDeploymentService.start(request);
            return ApiResponse.success("배포가 시작되었습니다.", response);
        } catch (Exception e) {
            log.error("Failed to start deployment", e);
            throw new RuntimeException("배포 시작 실패: " + e.getMessage(), e);
        }
    }

    @Override
    @GetMapping(value = "/api/v1/deploy/{deploymentId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@PathVariable String deploymentId) {
        log.info("SSE client connected for deployment: {}", deploymentId);
        return streamDeploymentEventsService.stream(deploymentId);
    }

    @Override
    @PostMapping("/api/v1/deploy/{deploymentId}/switch")
    public ApiResponse<?> switchTraffic(@PathVariable String deploymentId) {
        try {
            // 배포 결과 조회
            DeploymentResult result = getDeploymentResultService.getResult(deploymentId);

            // 배포 준비 상태 확인
            //if (!result.isDeploymentReady()) {
            //    throw new IllegalArgumentException("배포가 준비되지 않았습니다. 현재 상태: " + result.getStatus());
            //}

            // AWS 연결 정보 확인
            if (result.getAwsAccessKeyId() == null || result.getAwsSecretAccessKey() == null) {
                throw new IllegalArgumentException("AWS 연결 정보가 없습니다. 배포를 다시 시도해주세요.");
            }

            log.info("🚀 [Traffic Switch] Starting traffic switch for deployment: {}", deploymentId);

            // Lambda 호출: 배포 승인 (트래픽 전환)
            ApproveDeploymentRequest lambdaRequest = ApproveDeploymentRequest.builder()
                .deploymentId(deploymentId)
                .awsAccessKeyId(result.getAwsAccessKeyId())
                .awsSecretAccessKey(result.getAwsSecretAccessKey())
                .build();

            log.info("📤 [Lambda Invocation] Invoking lambda_4_appove_deployment with deploymentId: {}", deploymentId);
            ApproveDeploymentResponse lambdaResponse = lambdaInvocationService.invokeApproveDeploymentLambda(lambdaRequest);

            // Lambda 응답 검증
            lambdaInvocationService.validateApproveDeploymentResponse(lambdaResponse);

            // 배포 상태를 COMPLETED로 변경
            result.setStatus("COMPLETED");
            result.setCompletedAt(java.time.LocalDateTime.now());
            result.setFinalService(lambdaResponse.getActiveService() != null ?
                lambdaResponse.getActiveService() : "green");

            log.info("✅ [Traffic Switch Complete] Deployment completed - deploymentId: {}, activeService: {}",
                deploymentId, result.getFinalService());

            return ApiResponse.success("배포 전환이 시작되었습니다.", Map.of(
                    "deploymentId", deploymentId,
                    "message", lambdaResponse.getMessage() != null ?
                        lambdaResponse.getMessage() : "Traffic switching from blue to green in progress",
                    "activeService", result.getFinalService(),
                    "switchStatus", lambdaResponse.getSwitchStatus() != null ?
                        lambdaResponse.getSwitchStatus() : "IN_PROGRESS"
            ));
        } catch (Exception e) {
            log.error("❌ [Traffic Switch Failed] Failed to switch traffic for deployment: {}", deploymentId, e);
            throw new RuntimeException("배포 전환 실패: " + e.getMessage(), e);
        }
    }

    @Override
    @GetMapping("/api/v1/deploy/{deploymentId}/result")
    public ApiResponse<DeploymentResult> getDeploymentResult(@PathVariable String deploymentId) {
        DeploymentResult result = getDeploymentResultService.getResult(deploymentId);
        return ApiResponse.success("배포 결과 조회 성공", result);
    }

}
