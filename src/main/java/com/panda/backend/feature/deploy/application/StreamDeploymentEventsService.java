package com.panda.backend.feature.deploy.application;

import com.panda.backend.feature.deploy.event.DeploymentEvent;
import com.panda.backend.feature.deploy.event.DeploymentEventStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
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
            // 연결 확립 신호 전송 (클라이언트가 즉시 SSE 연결 확인 가능)
            emitter.send(SseEmitter.event()
                    .id(UUID.randomUUID().toString())
                    .name("connected")
                    .reconnectTime(5000)
                    .data(Map.of("message", "SSE connection established")));

            log.info("Connected event sent for deployment: {}", deploymentId);
        } catch (IOException e) {
            log.warn("Failed to send connected event for deployment: {}", deploymentId, e);
        }

        // 기존 이벤트 히스토리 전송
        sendEventHistory(deploymentId, emitter);

        return emitter;
    }

    private void sendEventHistory(String deploymentId, SseEmitter emitter) {
        List<DeploymentEvent> events = deploymentEventStore.getEventHistory(deploymentId);

        events.forEach(event -> {
            try {
                emitter.send(buildSseEvent(event));
            } catch (Exception e) {
                log.warn("Failed to send historical event to client for deployment: {}", deploymentId, e);
            }
        });
    }

    private SseEmitter.SseEventBuilder buildSseEvent(DeploymentEvent event) {
        String eventType = event.getType() != null ? event.getType() : "stage";

        SseEmitter.SseEventBuilder builder = SseEmitter.event()
                .id(UUID.randomUUID().toString())
                .name(eventType)
                .reconnectTime(5000);

        // Event 타입에 따라 데이터 설정
        if ("stage".equals(eventType)) {
            builder.data(event);
        } else if ("success".equals(eventType) || "fail".equals(eventType)) {
            builder.data(Map.of("message", event.getMessage()));
        }

        return builder;
    }
}
