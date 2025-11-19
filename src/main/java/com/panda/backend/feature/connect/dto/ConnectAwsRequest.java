package com.panda.backend.feature.connect.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "AWS 연결 요청")
public class ConnectAwsRequest {
    @Schema(description = "AWS 리전", example = "ap-northeast-2")
    private String region;

    @Schema(description = "AWS Access Key ID", example = "AKIAIOSFODNN7EXAMPLE")
    private String accessKeyId;

    @Schema(description = "AWS Secret Access Key", example = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
    private String secretAccessKey;

    @Schema(description = "AWS Session Token (선택사항)", example = "")
    private String sessionToken;
}
