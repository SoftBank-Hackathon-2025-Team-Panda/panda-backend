package com.panda.backend.feature.connect.api;

import com.panda.backend.feature.connect.application.SaveAwsConnectionService;
import com.panda.backend.feature.connect.application.SaveGitHubConnectionService;
import com.panda.backend.feature.connect.dto.ConnectAwsRequest;
import com.panda.backend.feature.connect.dto.ConnectAwsResponse;
import com.panda.backend.feature.connect.dto.ConnectGitHubRequest;
import com.panda.backend.feature.connect.dto.ConnectGitHubResponse;
import com.panda.backend.feature.connect.dto.AwsConnectionResponse;
import com.panda.backend.feature.connect.dto.ConnectionsListResponse;
import com.panda.backend.feature.connect.dto.GitHubConnectionResponse;
import com.panda.backend.feature.connect.infrastructure.ConnectionStore;
import com.panda.backend.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequiredArgsConstructor
public class ConnectController implements ConnectApi {

    private final SaveGitHubConnectionService saveGitHubConnectionService;
    private final SaveAwsConnectionService saveAwsConnectionService;
    private final ConnectionStore connectionStore;

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

    @Override
    @GetMapping("/api/v1/connections")
    public ApiResponse<ConnectionsListResponse> getConnections() {
        // GitHub 연결 정보 조회
        Map<String, Map<String, String>> gitHubConnections = connectionStore.getAllGitHubConnections();
        List<GitHubConnectionResponse> gitHubList = gitHubConnections.entrySet().stream()
                .map(entry -> new GitHubConnectionResponse(
                        entry.getKey(),
                        entry.getValue().get("owner"),
                        entry.getValue().get("repo"),
                        entry.getValue().get("branch")
                ))
                .collect(Collectors.toList());

        // AWS 연결 정보 조회
        Map<String, Map<String, String>> awsConnections = connectionStore.getAllAwsConnections();
        List<AwsConnectionResponse> awsList = awsConnections.entrySet().stream()
                .map(entry -> new AwsConnectionResponse(
                        entry.getKey(),
                        entry.getValue().get("region")
                ))
                .collect(Collectors.toList());

        ConnectionsListResponse response = new ConnectionsListResponse(gitHubList, awsList);
        log.info("Retrieved {} GitHub and {} AWS connections", gitHubList.size(), awsList.size());

        return ApiResponse.success("연결 정보를 조회했습니다.", response);
    }
}
