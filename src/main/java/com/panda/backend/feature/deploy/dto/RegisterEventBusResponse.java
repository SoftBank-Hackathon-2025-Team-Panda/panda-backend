package com.panda.backend.feature.deploy.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 서비스 계정의 Lambda (lambda_0_register_to_eventbus) 호출 응답
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegisterEventBusResponse {

    /**
     * 응답 상태: "OK" 또는 "ERROR"
     */
    private String status;

    /**
     * 상태 메시지
     */
    private String message;

    /**
     * 등록된 principal (성공 시만 존재)
     * 예: arn:aws:iam::123456789012:root
     */
    private String principal;

    /**
     * Event Bus ARN (성공 시만 존재)
     * 예: arn:aws:events:ap-northeast-2:654321098765:event-bus/softbank-event-bus
     */
    @JsonProperty("eventBusArn")
    private String eventBusArn;

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
