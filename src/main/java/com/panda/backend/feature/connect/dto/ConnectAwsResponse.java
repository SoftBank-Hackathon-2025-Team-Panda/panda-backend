package com.panda.backend.feature.connect.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(description = "AWS 연결 응답")
public class ConnectAwsResponse {
    @Schema(description = "AWS 연결 ID", example = "aws_1234567890")
    private String awsConnectionId;
}
