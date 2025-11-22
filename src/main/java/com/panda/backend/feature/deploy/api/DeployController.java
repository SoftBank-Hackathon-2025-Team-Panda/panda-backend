package com.panda.backend.feature.deploy.api;

import com.panda.backend.feature.deploy.application.GetDeploymentResultService;
import com.panda.backend.feature.deploy.application.StartDeploymentService;
import com.panda.backend.feature.deploy.application.StreamDeploymentEventsService;
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
            if (!result.isDeploymentReady()) {
                throw new IllegalArgumentException("배포가 준비되지 않았습니다. 현재 상태: " + result.getStatus());
            }

            // 배포 상태를 COMPLETED로 변경
            result.setStatus("COMPLETED");
            result.setCompletedAt(java.time.LocalDateTime.now());
            // 추가적으로 블루/그린 URL, 성능 메트릭 등을 업데이트할 수 있음

            // 배포 결과 저장
            // (DeploymentResultStore는 현재 메모리 저장소이므로, 변경된 result를 다시 저장)
            // 실제 구현에서는 여기서 finalService를 "green"으로 설정해야 함
            result.setFinalService("green");

            return ApiResponse.success("배포 전환이 시작되었습니다.", Map.of(
                    "deploymentId", deploymentId,
                    "message", "Traffic switching from blue to green in progress",
                    "activeService", "green"
            ));
        } catch (Exception e) {
            log.error("Failed to switch traffic for deployment: {}", deploymentId, e);
            throw new RuntimeException("배포 전환 실패: " + e.getMessage(), e);
        }
    }

    @Override
    @GetMapping("/api/v1/deploy/{deploymentId}/result")
    public ApiResponse<DeploymentResult> getDeploymentResult(@PathVariable String deploymentId) {
        DeploymentResult result = getDeploymentResultService.getResult(deploymentId);
        return ApiResponse.success("배포 결과 조회 성공", result);
    }

    // TODO: 성능 비교 API 구현
    // @GetMapping("/api/v1/deploy/{deploymentId}/performance")
    // public ApiResponse<PerformanceComparisonResult> comparePerformance(@PathVariable String deploymentId) {
    //   try {
    //     PerformanceComparisonResult result = performanceComparisonService.compare(deploymentId);
    //     return ApiResponse.success("성능 비교 완료", result);
    //   } catch (Exception e) {
    //     log.error("Failed to compare performance", e);
    //     throw new RuntimeException("성능 비교 실패: " + e.getMessage(), e);
    //   }
    // }
    //
    // 구현 필요:
    // 1. PerformanceComparisonService 주입
    // 2. 위의 메서드 구현 (위의 주석 제거)
    // 3. DeploymentResult에서 Blue/Green URL 조회
    // 4. 각 URL에 병렬로 5회씩 HTTP 요청
    // 5. 레이턴시, 에러율 측정 및 비교
}
