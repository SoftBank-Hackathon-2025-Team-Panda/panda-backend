package com.panda.backend.feature.deploy.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeploymentResult {

    // 배포 기본 정보
    private String deploymentId;
    private String status;              // RUNNING, DEPLOYMENT_READY, COMPLETED, FAILED
    private String owner;
    private String repo;
    private String branch;

    // 시간 정보
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long durationSeconds;       // 배포 소요 시간 (초)

    // 배포 결과
    private String finalService;        // blue or green
    private String blueUrl;             // Blue 서비스 URL
    private String greenUrl;            // Green 서비스 URL
    private String blueServiceArn;      // Blue 서비스 ARN (ECS)
    private String greenServiceArn;     // Green 서비스 ARN (ECS)
    private String errorMessage;        // 실패 시 에러 메시지

    // 성능 메트릭
    private Long blueLatencyMs;
    private Long greenLatencyMs;
    private Double blueErrorRate;
    private Double greenErrorRate;

    // 이벤트 정보
    private Integer eventCount;         // 발행된 이벤트 개수

    // AWS 연결 정보 (Lambda 호출 시 필요)
    private String awsAccessKeyId;
    private String awsSecretAccessKey;
    private String awsSessionToken;

    public boolean isSuccessful() {
        return "COMPLETED".equals(status);
    }

    public boolean isFailed() {
        return "FAILED".equals(status);
    }

    public boolean isDeploymentReady() {
        return "DEPLOYMENT_READY".equals(status);
    }

    public boolean isCompleted() {
        return isSuccessful() || isFailed() || isDeploymentReady();
    }

    public String getFasterService() {
        if (blueLatencyMs == null || greenLatencyMs == null) {
            return null;
        }
        return blueLatencyMs < greenLatencyMs ? "blue" : "green";
    }

    public Double getLatencyImprovement() {
        if (blueLatencyMs == null || greenLatencyMs == null || blueLatencyMs == 0) {
            return null;
        }
        return ((double) (blueLatencyMs - greenLatencyMs) / blueLatencyMs) * 100;
    }

    public String getFormattedDuration() {
        if (durationSeconds == null) {
            return "N/A";
        }
        long hours = durationSeconds / 3600;
        long minutes = (durationSeconds % 3600) / 60;
        long seconds = durationSeconds % 60;

        if (hours > 0) {
            return String.format("%dh %dm %ds", hours, minutes, seconds);
        } else if (minutes > 0) {
            return String.format("%dm %ds", minutes, seconds);
        } else {
            return String.format("%ds", seconds);
        }
    }
}
