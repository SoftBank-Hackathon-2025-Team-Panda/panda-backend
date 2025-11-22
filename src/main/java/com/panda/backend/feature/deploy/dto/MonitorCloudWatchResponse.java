package com.panda.backend.feature.deploy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * CloudWatch 메트릭 모니터링 Lambda 호출 응답
 *
 * Lambda 함수: lambda_1_monitor_cloudwatch
 * 응답 예시:
 * {
 *   "status": "SUCCESS",
 *   "message": "CloudWatch metrics collected successfully",
 *   "blueLatencyMs": 250,
 *   "greenLatencyMs": 180,
 *   "blueErrorRate": 0.01,
 *   "greenErrorRate": 0.005,
 *   "blueCpuUtilization": 35.5,
 *   "greenCpuUtilization": 42.1,
 *   "blueMemoryUtilization": 45.2,
 *   "greenMemoryUtilization": 38.9,
 *   "blueRequestCount": 1050,
 *   "greenRequestCount": 1100
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonitorCloudWatchResponse {

    /** 상태: SUCCESS, FAILURE */
    private String status;

    /** 응답 메시지 */
    private String message;

    /** Blue 서비스 응답 지연시간 (ms) */
    @JsonProperty("blueLatencyMs")
    private Long blueLatencyMs;

    /** Green 서비스 응답 지연시간 (ms) */
    @JsonProperty("greenLatencyMs")
    private Long greenLatencyMs;

    /** Blue 서비스 에러율 (0.0 ~ 1.0) */
    @JsonProperty("blueErrorRate")
    private Double blueErrorRate;

    /** Green 서비스 에러율 (0.0 ~ 1.0) */
    @JsonProperty("greenErrorRate")
    private Double greenErrorRate;

    /** Blue 서비스 CPU 사용률 (%) */
    @JsonProperty("blueCpuUtilization")
    private Double blueCpuUtilization;

    /** Green 서비스 CPU 사용률 (%) */
    @JsonProperty("greenCpuUtilization")
    private Double greenCpuUtilization;

    /** Blue 서비스 메모리 사용률 (%) */
    @JsonProperty("blueMemoryUtilization")
    private Double blueMemoryUtilization;

    /** Green 서비스 메모리 사용률 (%) */
    @JsonProperty("greenMemoryUtilization")
    private Double greenMemoryUtilization;

    /** Blue 서비스 요청 수 */
    @JsonProperty("blueRequestCount")
    private Long blueRequestCount;

    /** Green 서비스 요청 수 */
    @JsonProperty("greenRequestCount")
    private Long greenRequestCount;

    /** 추가 메타데이터 */
    private Map<String, Object> metadata;

    public boolean isSuccess() {
        return "SUCCESS".equalsIgnoreCase(status);
    }

    public boolean isFailure() {
        return "FAILURE".equalsIgnoreCase(status);
    }
}
