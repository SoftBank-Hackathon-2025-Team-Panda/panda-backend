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

    // deploymentId -> keepalive thread (주기적 connected 이벤트 전송 스레드)
    private final Map<String, Thread> keepaliveThreadMap = new ConcurrentHashMap<>();

    // 새로운 SSE 클라이언트 연결 등록
    public SseEmitter registerEmitter(String deploymentId) {

        SseEmitter emitter = new SseEmitter(600000L); // 10분 타임아웃 (5분 -> 10분으로 증가)

        emitterMap.computeIfAbsent(deploymentId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(emitter);

        emitter.onCompletion(() -> {
            removeEmitter(deploymentId, emitter);
            stopKeepalive(deploymentId);
        });
        emitter.onTimeout(() -> {
            removeEmitter(deploymentId, emitter);
            stopKeepalive(deploymentId);
        });
        emitter.onError((throwable) -> {
            removeEmitter(deploymentId, emitter);
            stopKeepalive(deploymentId);
        });

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
                            .reconnectTime(3000);  // 재연결 시간 단축 (5초 -> 3초)

                    // 모든 이벤트 타입에 전체 데이터 전송 (stage, success, fail 모두)
                    if ("stage".equals(eventType) || "success".equals(eventType) || "fail".equals(eventType)) {
                        eventBuilder.data(event);
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
        // ✅ Deployment ready 이벤트 전에 connected 이벤트 먼저 전송
        sendConnectedEvent(deploymentId);

        DeploymentEvent event = new DeploymentEvent();
        event.setType("stage");
        event.setMessage("Green environment is being prepared. This may take a few minutes.");

        // 통일된 형식: stage, timestamp는 항상 포함, 추가 details는 merge
        Map<String, Object> unifiedDetails = new java.util.HashMap<>();
        unifiedDetails.put("stage", 4);
        unifiedDetails.put("timestamp", java.time.Instant.now().toString());
        if (details != null) {
            unifiedDetails.putAll(details);
        }
        event.setDetails(unifiedDetails);

        broadcastEvent(deploymentId, event);

        // 배포 결과 저장
        saveDeploymentResult(deploymentId, "DEPLOYMENT_READY");

        log.info("Deployment ready event sent for deploymentId: {}", deploymentId);
    }

    // "fail" 이벤트 전송 (배포 실패) - 상세정보 포함
    public void sendErrorEvent(String deploymentId, String message, Map<String, Object> errorDetails) {
        DeploymentEvent event = new DeploymentEvent();
        event.setType("fail");
        event.setMessage(message);

        // 통일된 형식: stage, stepFunctionsStage, timestamp는 항상 포함
        Map<String, Object> unifiedDetails = new java.util.HashMap<>();
        unifiedDetails.put("timestamp", java.time.Instant.now().toString());
        if (errorDetails != null) {
            unifiedDetails.putAll(errorDetails);  // stage, stepFunctionsStage 포함
        }
        event.setDetails(unifiedDetails);

        log.info("📤 [Error Event] type: fail, message: {}, details: {}", message, event.getDetails());

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
        // Keepalive 중지
        stopKeepalive(deploymentId);

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

    // Connected 이벤트 전송 (stage 이벤트 직전에 연결 상태 확인)
    public void sendConnectedEvent(String deploymentId) {
        List<SseEmitter> emitters = emitterMap.get(deploymentId);
        if (emitters != null && !emitters.isEmpty()) {
            List<SseEmitter> failedEmitters = new ArrayList<>();

            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event()
                            .id(UUID.randomUUID().toString())
                            .name("connected")
                            .reconnectTime(3000)
                            .data(Map.of("message", "SSE connection active")));
                } catch (IOException e) {
                    log.warn("Failed to send connected event to emitter for deployment: {}", deploymentId, e);
                    failedEmitters.add(emitter);
                }
            }

            // 실패한 emitter 제거
            for (SseEmitter failedEmitter : failedEmitters) {
                removeEmitter(deploymentId, failedEmitter);
            }
        }
    }

    // Keepalive 시작 (주기적으로 주석 이벤트 전송하여 연결 유지)
    public void startKeepalive(String deploymentId) {
        // 이미 keepalive가 실행 중이면 중복 시작 방지
        if (keepaliveThreadMap.containsKey(deploymentId)) {
            Thread existingThread = keepaliveThreadMap.get(deploymentId);
            if (existingThread != null && existingThread.isAlive()) {
                log.debug("Keepalive already running for deployment: {}", deploymentId);
                return;
            }
        }

        Thread keepaliveThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(30000); // 30초마다 keepalive 전송

                    List<SseEmitter> emitters = emitterMap.get(deploymentId);
                    if (emitters == null || emitters.isEmpty()) {
                        log.debug("No emitters found for deployment: {}, stopping keepalive", deploymentId);
                        break;
                    }

                    List<SseEmitter> failedEmitters = new ArrayList<>();

                    for (SseEmitter emitter : emitters) {
                        try {
                            // 주석 이벤트로 keepalive 전송 (프록시/로드밸런서가 연결을 끊지 않도록)
                            emitter.send(SseEmitter.event()
                                    .id(UUID.randomUUID().toString())
                                    .comment("keepalive")  // 주석 이벤트는 클라이언트에서 무시됨
                                    .reconnectTime(3000));
                        } catch (IOException e) {
                            log.debug("Failed to send keepalive to emitter for deployment: {}", deploymentId, e);
                            failedEmitters.add(emitter);
                        }
                    }

                    // 실패한 emitter 제거
                    for (SseEmitter failedEmitter : failedEmitters) {
                        removeEmitter(deploymentId, failedEmitter);
                    }

                    // 모든 emitter가 제거되면 keepalive 종료
                    if (emitters.isEmpty() || (emitters.size() == failedEmitters.size())) {
                        log.debug("All emitters removed for deployment: {}, stopping keepalive", deploymentId);
                        break;
                    }
                }
            } catch (InterruptedException e) {
                log.debug("Keepalive thread interrupted for deployment: {}", deploymentId);
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.error("Error in keepalive thread for deployment: {}", deploymentId, e);
            } finally {
                keepaliveThreadMap.remove(deploymentId);
                log.debug("Keepalive thread stopped for deployment: {}", deploymentId);
            }
        });

        keepaliveThread.setDaemon(true);
        keepaliveThread.setName("SSE-Keepalive-" + deploymentId);
        keepaliveThread.start();
        keepaliveThreadMap.put(deploymentId, keepaliveThread);
        log.info("Keepalive started for deployment: {}", deploymentId);
    }

    // Keepalive 중지
    public void stopKeepalive(String deploymentId) {
        Thread keepaliveThread = keepaliveThreadMap.remove(deploymentId);
        if (keepaliveThread != null && keepaliveThread.isAlive()) {
            keepaliveThread.interrupt();
            log.debug("Keepalive stopped for deployment: {}", deploymentId);
        }
    }

}
