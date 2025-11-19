package com.panda.backend.feature.connect.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(description = "GitHub 연결 응답")
public class ConnectGitHubResponse {
    @Schema(description = "GitHub 연결 ID", example = "gh_1234567890")
    private String githubConnectionId;
}
