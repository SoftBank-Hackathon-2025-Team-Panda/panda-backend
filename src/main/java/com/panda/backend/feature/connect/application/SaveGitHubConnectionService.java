package com.panda.backend.feature.connect.application;

import com.panda.backend.feature.connect.dto.ConnectGitHubRequest;
import com.panda.backend.feature.connect.dto.ConnectGitHubResponse;
import com.panda.backend.feature.connect.entity.GitHubConnection;
import com.panda.backend.feature.connect.infrastructure.ConnectionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SaveGitHubConnectionService {

    private final GitHubConnectionService gitHubConnectionService;
    private final ConnectionStore connectionStore;

    public ConnectGitHubResponse save(ConnectGitHubRequest request) {
        try {
            // GitHub 연결 검증
            gitHubConnectionService.validateAndConnectGitHub(
                    request.getToken(),
                    request.getOwner(),
                    request.getRepo()
            );

            // 연결 저장
            GitHubConnection ghConnection = new GitHubConnection(
                    request.getOwner(),
                    request.getRepo(),
                    request.getBranch(),
                    request.getToken()
            );
            String connectionId = connectionStore.saveGitHubConnection(ghConnection);

            log.info("GitHub connection saved with ID: {}", connectionId);
            return new ConnectGitHubResponse(connectionId);
        } catch (Exception e) {
            log.error("GitHub connection failed", e);
            throw new RuntimeException("GitHub 연결 실패: " + e.getMessage(), e);
        }
    }
}
