package com.panda.backend.feature.deploy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "배포 요청")
public class DeployRequest {
    @Schema(description = "GitHub 연결 ID", example = "gh_1234567890")
    private String githubConnectionId;

    @Schema(description = "AWS 연결 ID", example = "aws_1234567890")
    private String awsConnectionId;

    @Schema(description = "GitHub 조직 또는 사용자명", example = "your-org")
    private String owner;

    @Schema(description = "GitHub 레포지토리명", example = "your-repo")
    private String repo;

    @Schema(description = "레포지토리 브랜치", example = "main")
    private String branch;
}
