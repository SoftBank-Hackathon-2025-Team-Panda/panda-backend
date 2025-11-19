package com.panda.backend.feature.deploy.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
@Schema(description = "배포 응답")
public class DeployResponse {
    @Schema(description = "배포 ID", example = "dep_1234567890")
    private String deploymentId;

    @Schema(description = "메시지", example = "Deployment started. Listen to /deploy/{id}/events")
    private String message;
}
