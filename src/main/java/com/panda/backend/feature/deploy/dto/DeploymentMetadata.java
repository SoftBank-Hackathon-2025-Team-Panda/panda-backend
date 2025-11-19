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
public class DeploymentMetadata {

    // 배포 기본 정보
    private String deploymentId;
    private String status;              // IN_PROGRESS, COMPLETED, FAILED
    private Integer currentStage;        // 1-6 stage 번호

    // GitHub 정보
    private String owner;
    private String repo;
    private String branch;

    // AWS 정보
    private String awsRegion;

    // 시간 정보
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;

    // 배포 결과
    private String errorMessage;        // 실패 시 에러 메시지
    private String finalService;        // blue or green
    private String blueUrl;             // Blue 서비스 URL
    private String greenUrl;            // Green 서비스 URL

    // TODO: 성능 메트릭 수집 (Stage 5 헬스체크 완료 후 저장)
    // 현재: 필드만 정의되어 있고 실제 값 저장 미구현
    // 구현 필요:
    // 1. Stage 5 헬스체크에서 실제 레이턴시 측정
    // 2. Green 서비스에 5회 요청하여 평균 레이턴시 계산
    // 3. 에러율 계산 (실패 횟수 / 총 요청 수)
    // 4. 메트릭을 DeploymentMetadata에 저장
    // 5. DeploymentResult 생성 시 메트릭 포함
    private Long blueLatencyMs;
    private Long greenLatencyMs;
    private Double blueErrorRate;
    private Double greenErrorRate;

    /**
     * 배포 진행 시간 (초 단위)
     */
    public Long getDurationInSeconds() {
        if (startedAt == null) return null;
        LocalDateTime end = completedAt != null ? completedAt : LocalDateTime.now();
        return java.time.temporal.ChronoUnit.SECONDS.between(startedAt, end);
    }

    /**
     * 배포 진행 상태 확인
     */
    public boolean isInProgress() {
        return "IN_PROGRESS".equals(status);
    }

    public boolean isCompleted() {
        return "COMPLETED".equals(status);
    }

    public boolean isFailed() {
        return "FAILED".equals(status);
    }
}
