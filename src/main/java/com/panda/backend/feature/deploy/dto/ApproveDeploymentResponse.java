package com.panda.backend.feature.deploy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 서비스 계정의 Lambda (lambda_4_appove_deployment) 호출 응답
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApproveDeploymentResponse {

    /**
     * 응답 상태: "OK" 또는 "ERROR"
     */
    private String status;

    /**
     * 상태 메시지
     */
    private String message;

    /**
     * 배포 ID
     */
    private String deploymentId;

    /**
     * 트래픽 전환 상태
     * 예: "IN_PROGRESS", "COMPLETED"
     */
    @JsonProperty("switchStatus")
    private String switchStatus;

    /**
     * 활성화된 서비스
     * 예: "green"
     */
    @JsonProperty("activeService")
    private String activeService;

    /**
     * 성공 여부
     */
    public boolean isSuccess() {
        return "OK".equalsIgnoreCase(status) || "SUCCESS".equalsIgnoreCase(status);
    }

    /**
     * 실패 여부
     */
    public boolean isFailure() {
        return "ERROR".equalsIgnoreCase(status);
    }
}
