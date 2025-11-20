package com.panda.backend.feature.connect.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(description = "저장된 AWS 연결 정보")
public class AwsConnectionResponse {
    @Schema(description = "AWS 연결 ID", example = "aws_1234567890")
    private String connectionId;

    @Schema(description = "AWS 리전", example = "ap-northeast-2")
    private String region;
}
