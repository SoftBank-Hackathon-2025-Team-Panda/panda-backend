package com.panda.backend.feature.connect.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "GitHub 연결 요청")
public class ConnectGitHubRequest {
    @Schema(description = "GitHub 조직 또는 사용자명", example = "your-org")
    private String owner;

    @Schema(description = "GitHub 레포지토리명", example = "your-repo")
    private String repo;

    @Schema(description = "레포지토리 브랜치", example = "main")
    private String branch;

    @Schema(description = "GitHub Personal Access Token", example = "ghp_xxxxx")
    private String token;
}
