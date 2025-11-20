package com.panda.backend.feature.connect.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(description = "저장된 GitHub 연결 정보")
public class GitHubConnectionResponse {
    @Schema(description = "GitHub 연결 ID", example = "gh_1234567890")
    private String connectionId;

    @Schema(description = "GitHub 리포지토리 소유자", example = "octocat")
    private String owner;

    @Schema(description = "GitHub 리포지토리 이름", example = "panda")
    private String repo;

    @Schema(description = "배포할 브랜치", example = "main")
    private String branch;
}
