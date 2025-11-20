package com.panda.backend.feature.connect.application;

import com.panda.backend.feature.connect.dto.ConnectAwsRequest;
import com.panda.backend.feature.connect.dto.ConnectAwsResponse;
import com.panda.backend.feature.connect.entity.AwsConnection;
import com.panda.backend.feature.connect.infrastructure.ConnectionStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SaveAwsConnectionService {

    private final AwsConnectionService awsConnectionService;
    private final ConnectionStore connectionStore;

    public ConnectAwsResponse save(ConnectAwsRequest request) {
        try {
            // AWS 자격증명 검증
            awsConnectionService.validateAwsCredentials(
                    request.getRegion(),
                    request.getAccessKeyId(),
                    request.getSecretAccessKey(),
                    request.getSessionToken()
            );

            // 연결 저장
            AwsConnection awsConnection = new AwsConnection(
                    request.getRegion(),
                    request.getAccessKeyId(),
                    request.getSecretAccessKey(),
                    request.getSessionToken()
            );
            String connectionId = connectionStore.saveAwsConnection(awsConnection, request.getRegion());

            log.info("AWS connection saved with ID: {}", connectionId);
            return new ConnectAwsResponse(connectionId);
        } catch (Exception e) {
            log.error("AWS connection failed", e);
            throw new RuntimeException("AWS 연결 실패: " + e.getMessage(), e);
        }
    }
}
