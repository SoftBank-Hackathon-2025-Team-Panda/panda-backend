package com.panda.backend.feature.connect.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

@Data
@AllArgsConstructor
@Schema(description = "저장된 모든 연결 정보")
public class ConnectionsListResponse {
    @Schema(description = "GitHub 연결 목록")
    private List<GitHubConnectionResponse> github;

    @Schema(description = "AWS 연결 목록")
    private List<AwsConnectionResponse> aws;
}
