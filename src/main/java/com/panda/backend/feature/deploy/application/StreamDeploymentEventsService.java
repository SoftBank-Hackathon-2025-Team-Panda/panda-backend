package com.panda.backend.feature.deploy.application;

import com.panda.backend.feature.deploy.event.DeploymentEvent;
import com.panda.backend.feature.deploy.event.DeploymentEventStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StreamDeploymentEventsService {

    private final DeploymentEventStore deploymentEventStore;

    public SseEmitter stream(String deploymentId) {
        // Emitter 등록
        SseEmitter emitter = deploymentEventStore.registerEmitter(deploymentId);

        try {
            emitter.send(SseEmitter.event()
                    .id(UUID.randomUUID().toString())
                    .name("connected")
                    .reconnectTime(3000)  // 재연결 시간 단축 (5초 -> 3초)
                    .data(Map.of("message", "SSE connection established")));

            log.info("Connected event sent immediately for deployment: {}", deploymentId);
        } catch (IOException e) {
            log.warn("Failed to send connected event for deployment: {}", deploymentId, e);
        }

        // Keepalive 시작
        deploymentEventStore.startKeepalive(deploymentId);

        return emitter;
    }

    private SseEmitter.SseEventBuilder buildSseEvent(DeploymentEvent event) {
        String eventType = event.getType() != null ? event.getType() : "stage";

        SseEmitter.SseEventBuilder builder = SseEmitter.event()
                .id(UUID.randomUUID().toString())
                .name(eventType)
                .reconnectTime(3000);  // 재연결 시간 단축 (5초 -> 3초)

        // Event 타입에 따라 데이터 설정
        if ("stage".equals(eventType)) {
            builder.data(event);
        } else if ("success".equals(eventType) || "fail".equals(eventType)) {
            builder.data(Map.of("message", event.getMessage()));
        }

        return builder;
    }
}
