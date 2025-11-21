package com.panda.backend.feature.deploy.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 서비스 계정의 Lambda (lambda_0_register_to_eventbus) 호출 요청
 * 사용자 계정의 AWS 정보를 전달하여 EventBus 권한 설정 요청
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegisterEventBusRequest {

    /**
     * 사용자 계정의 AWS Access Key ID
     */
    private String awsAccessKeyId;

    /**
     * 사용자 계정의 AWS Secret Access Key
     */
    private String awsSecretAccessKey;

    /**
     * AWS Region
     */
    private String region;
}
