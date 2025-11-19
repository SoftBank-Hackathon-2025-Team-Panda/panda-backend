package com.panda.backend.feature.connect.api;

import com.panda.backend.feature.connect.dto.ConnectAwsRequest;
import com.panda.backend.feature.connect.dto.ConnectAwsResponse;
import com.panda.backend.feature.connect.dto.ConnectGitHubRequest;
import com.panda.backend.feature.connect.dto.ConnectGitHubResponse;
import com.panda.backend.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@Tag(name = "Connection", description = "GitHub 및 AWS 연결 관리")
public interface ConnectApi {

    @PostMapping("/api/v1/connect/github")
    @Operation(summary = "GitHub 레포 연결", description = "GitHub 레포 정보를 검증하고 연결을 생성합니다.")
    ApiResponse<ConnectGitHubResponse> connectGitHub(@RequestBody ConnectGitHubRequest request);

    @PostMapping("/api/v1/connect/aws")
    @Operation(summary = "AWS 계정 연결", description = "AWS 자격증명을 검증하고 연결을 생성합니다.")
    ApiResponse<ConnectAwsResponse> connectAws(@RequestBody ConnectAwsRequest request);
}
