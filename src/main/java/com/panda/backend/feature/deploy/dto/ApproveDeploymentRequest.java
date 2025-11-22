package com.panda.backend.feature.deploy.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 서비스 계정의 Lambda (lambda_4_appove_deployment) 호출 요청
 * 트래픽 전환 승인을 위해 배포 ID와 AWS 자격증명을 전달
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApproveDeploymentRequest {

    /**
     * 배포 ID
     */
    private String deploymentId;

    /**
     * 사용자 계정의 AWS Access Key ID
     */
    private String awsAccessKeyId;

    /**
     * 사용자 계정의 AWS Secret Access Key
     */
    private String awsSecretAccessKey;
}
