package com.panda.backend.feature.deploy.api;

import com.panda.backend.feature.deploy.dto.DeployRequest;
import com.panda.backend.feature.deploy.dto.DeployResponse;
import com.panda.backend.feature.deploy.dto.DeploymentResult;
import com.panda.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.servlet.http.HttpServletResponse;

@Tag(name = "Deployment", description = "배포 파이프라인 관리 (필수 3개 API)")
public interface DeployApi {

    @PostMapping("/api/v1/deploy")
    @Operation(
        summary = "배포 시작",
        description = "GitHub clone → Docker build → ECR Push → ECS 배포를 시작합니다. " +
                     "즉시 deploymentId를 반환하고, 배포는 백그라운드에서 진행됩니다."
    )
    ApiResponse<DeployResponse> deploy(@RequestBody DeployRequest request);

    @GetMapping(value = "/api/v1/deploy/{deploymentId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
        summary = "배포 실시간 이벤트 스트리밍 (SSE)",
        description = "배포 진행 상황을 Stage별로 실시간 스트리밍합니다. " +
                     "과거 이벤트 히스토리도 자동으로 전송되어 중간에 접속한 클라이언트도 진행 상황을 파악할 수 있습니다."
    )
    SseEmitter streamEvents(@PathVariable String deploymentId, HttpServletResponse response);

    @PostMapping("/api/v1/deploy/{deploymentId}/switch")
    @Operation(
        summary = "배포 전환 실행 (수동 확인)",
        description = "Stage 4에서 Green 서비스 배포가 완료되면, 사용자가 준비 상태를 확인한 후 이 API를 호출하여 " +
                     "트래픽을 Blue에서 Green으로 전환합니다."
    )
    ApiResponse<?> switchTraffic(@PathVariable String deploymentId);

    @GetMapping("/api/v1/deploy/{deploymentId}/result")
    @Operation(
        summary = "배포 최종 결과 조회",
        description = "배포가 완료된 후 최종 결과를 조회합니다. " +
                     "배포 상태, 소요 시간, Blue/Green URL, 성능 메트릭 등을 반환합니다."
    )
    ApiResponse<DeploymentResult> getDeploymentResult(@PathVariable String deploymentId);

    // TODO: 성능 비교 API 추가
    // @GetMapping("/api/v1/deploy/{deploymentId}/performance")
    // @Operation(
    //   summary = "Blue/Green 성능 비교",
    //   description = "Blue와 Green 서비스의 성능을 비교합니다. " +
    //                "각 서비스에 5회씩 요청하여 레이턴시와 에러율을 측정합니다."
    // )
    // ApiResponse<PerformanceComparisonResult> comparePerformance(@PathVariable String deploymentId);
    //
    // 구현 필요:
    // 1. PerformanceApi 메서드 추가
    // 2. PerformanceController 메서드 구현
    // 3. PerformanceComparisonService 생성
    // 4. PerformanceResult/PerformanceComparisonResult DTO 생성
    // 5. HTTP 클라이언트로 Blue/Green URL에 병렬 요청
    // 6. 레이턴시, 에러율 측정 및 비교
}
