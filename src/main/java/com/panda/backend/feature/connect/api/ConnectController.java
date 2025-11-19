package com.panda.backend.feature.connect.api;

import com.panda.backend.feature.connect.application.SaveAwsConnectionService;
import com.panda.backend.feature.connect.application.SaveGitHubConnectionService;
import com.panda.backend.feature.connect.dto.ConnectAwsRequest;
import com.panda.backend.feature.connect.dto.ConnectAwsResponse;
import com.panda.backend.feature.connect.dto.ConnectGitHubRequest;
import com.panda.backend.feature.connect.dto.ConnectGitHubResponse;
import com.panda.backend.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ConnectController implements ConnectApi {

    private final SaveGitHubConnectionService saveGitHubConnectionService;
    private final SaveAwsConnectionService saveAwsConnectionService;

    @Override
    @PostMapping("/api/v1/connect/github")
    public ApiResponse<ConnectGitHubResponse> connectGitHub(@RequestBody ConnectGitHubRequest request) {
        ConnectGitHubResponse response = saveGitHubConnectionService.save(request);
        return ApiResponse.success("GitHub 연결에 성공했습니다.", response);
    }

    @Override
    @PostMapping("/api/v1/connect/aws")
    public ApiResponse<ConnectAwsResponse> connectAws(@RequestBody ConnectAwsRequest request) {
        ConnectAwsResponse response = saveAwsConnectionService.save(request);
        return ApiResponse.success("AWS 연결에 성공했습니다.", response);
    }
}
