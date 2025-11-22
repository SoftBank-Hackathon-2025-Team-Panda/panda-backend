package com.panda.backend.feature.deploy.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class DeploymentEventStore {

    // deploymentId -> List of SseEmitters
    private final Map<String, List<SseEmitter>> emitterMap = new ConcurrentHashMap<>();

    // deploymentId -> List of events (히스토리)
    private final Map<String, Queue<DeploymentEvent>> eventHistoryMap = new ConcurrentHashMap<>();

    // 새로운 SSE 클라이언트 연결 등록
    public SseEmitter registerEmitter(String deploymentId) {

        SseEmitter emitter = new SseEmitter(300000L); // 5분 타임아웃

        emitterMap.computeIfAbsent(deploymentId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(emitter);

        emitter.onCompletion(() -> removeEmitter(deploymentId, emitter));
        emitter.onTimeout(() -> removeEmitter(deploymentId, emitter));
        emitter.onError((throwable) -> removeEmitter(deploymentId, emitter));

        log.info("SSE emitter registered for deployment: {}", deploymentId);
        return emitter;
    }

    // SSE 클라이언트 연결 제거
    private void removeEmitter(String deploymentId, SseEmitter emitter) {
        List<SseEmitter> emitters = emitterMap.get(deploymentId);
        if (emitters != null) {
            emitters.remove(emitter);
            log.info("SSE emitter removed for deployment: {}, remaining: {}", deploymentId, emitters.size());
        }
    }

    // 모든 연결된 클라이언트에게 이벤트 전송
    public void broadcastEvent(String deploymentId, DeploymentEvent event) {
        // 이벤트 히스토리에 저장
        eventHistoryMap.computeIfAbsent(deploymentId, k -> new LinkedList<>())
                .offer(event);

        // 모든 연결된 클라이언트에게 전송
        List<SseEmitter> emitters = emitterMap.get(deploymentId);
        if (emitters != null && !emitters.isEmpty()) {
            List<SseEmitter> failedEmitters = new ArrayList<>();

            for (SseEmitter emitter : emitters) {
                try {
                    // event type에 따라 다른 event name 설정
                    String eventType = event.getType() != null ? event.getType() : "stage";

                    SseEmitter.SseEventBuilder eventBuilder = SseEmitter.event()
                            .id(UUID.randomUUID().toString())
                            .name(eventType)
                            .reconnectTime(5000);

                    // "stage" 타입만 data 포함, "success"와 "fail"는 message만 전송
                    if ("stage".equals(eventType)) {
                        eventBuilder.data(event);
                    } else if ("success".equals(eventType) || "fail".equals(eventType)) {
                        eventBuilder.data(Map.of("message", event.getMessage()));
                    }

                    emitter.send(eventBuilder);
                } catch (IOException e) {
                    log.warn("Failed to send event to emitter for deployment: {}", deploymentId, e);
                    failedEmitters.add(emitter);
                }
            }

            // 실패한 emitter 제거
            for (SseEmitter failedEmitter : failedEmitters) {
                removeEmitter(deploymentId, failedEmitter);
            }
        }
    }

    // "success" 이벤트 전송 (배포 완료)
    public void sendDoneEvent(String deploymentId, String message) {
        DeploymentEvent event = new DeploymentEvent();
        event.setType("success");
        event.setMessage(message);

        broadcastEvent(deploymentId, event);

        // 배포 결과 저장
        saveDeploymentResult(deploymentId, "COMPLETED");

        // 이벤트 전송 후 5초 후에 모든 연결 종료
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                closeAllEmitters(deploymentId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    // "deployment ready" 상태 전송 (배포 준비 완료, 수동 전환 대기)
    public void sendDeploymentReadyEvent(String deploymentId, Map<String, Object> details) {
        DeploymentEvent event = new DeploymentEvent();
        event.setType("stage");
        event.setMessage("[Stage 4] Green 서비스 배포 완료 - 트래픽 전환 대기 중");
        event.setDetails(details != null ? details : Map.of("stage", 4));

        broadcastEvent(deploymentId, event);

        // 배포 결과 저장
        saveDeploymentResult(deploymentId, "DEPLOYMENT_READY");

        log.info("Deployment ready event sent for deploymentId: {}", deploymentId);
    }

    // "fail" 이벤트 전송 (배포 실패)
    public void sendErrorEvent(String deploymentId, String message) {
        DeploymentEvent event = new DeploymentEvent();
        event.setType("fail");
        event.setMessage(message);

        broadcastEvent(deploymentId, event);

        // 배포 결과 저장
        saveDeploymentResult(deploymentId, "FAILED");

        // 에러 전송 후 5초 후에 모든 연결 종료
        new Thread(() -> {
            try {
                Thread.sleep(5000);
                closeAllEmitters(deploymentId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    // 모든 SSE 클라이언트 연결 종료
    public void closeAllEmitters(String deploymentId) {
        List<SseEmitter> emitters = emitterMap.remove(deploymentId);
        if (emitters != null) {
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.complete();
                } catch (Exception e) {
                    log.warn("Failed to complete emitter for deployment: {}", deploymentId, e);
                }
            }
            log.info("All emitters closed for deployment: {}", deploymentId);
        }
    }

    // 배포 이벤트 히스토리 조회 (신규 클라이언트가 기존 진행 상황을 받을 수 있도록)
    public List<DeploymentEvent> getEventHistory(String deploymentId) {
        Queue<DeploymentEvent> events = eventHistoryMap.get(deploymentId);
        return events != null ? new ArrayList<>(events) : new ArrayList<>();
    }

    // 배포 결과 저장 (배포 완료/실패 시)
    private void saveDeploymentResult(String deploymentId, String status) {
        try {
            log.info("Deployment result saved - deploymentId: {}, status: {}", deploymentId, status);
        } catch (Exception e) {
            log.error("Failed to save deployment result for deploymentId: {}", deploymentId, e);
        }
    }

    // 이벤트 히스토리 크기 조회
    public Integer getEventHistorySize(String deploymentId) {
        Queue<DeploymentEvent> events = eventHistoryMap.get(deploymentId);
        return events != null ? events.size() : 0;
    }

}
