package com.panda.backend.feature.deploy.event;

import com.panda.backend.feature.deploy.dto.DeploymentMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class DeploymentEventStore {

    // deploymentId -> List of SseEmitters
    private final Map<String, List<SseEmitter>> emitterMap = new ConcurrentHashMap<>();

    // deploymentId -> List of events (히스토리)
    private final Map<String, Queue<DeploymentEvent>> eventHistoryMap = new ConcurrentHashMap<>();

    // deploymentId -> deployment metadata (배포 메타데이터)
    private final Map<String, DeploymentMetadata> metadataMap = new ConcurrentHashMap<>();

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

                    // "stage" 타입만 data 포함, "done"과 "error"는 message만 전송
                    if ("stage".equals(eventType)) {
                        eventBuilder.data(event);
                    } else if ("done".equals(eventType) || "error".equals(eventType)) {
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

    // "done" 이벤트 전송 (배포 완료)
    public void sendDoneEvent(String deploymentId, String message) {
        DeploymentEvent event = new DeploymentEvent();
        event.setType("done");
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

    // "error" 이벤트 전송 (배포 실패)
    public void sendErrorEvent(String deploymentId, String message) {
        DeploymentEvent event = new DeploymentEvent();
        event.setType("error");
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

    // ==================== 메타데이터 관리 메서드 ====================

    // 배포 시작 시 메타데이터 생성
    public void initializeMetadata(String deploymentId, String owner, String repo, String branch, String awsRegion) {
        DeploymentMetadata metadata = DeploymentMetadata.builder()
                .deploymentId(deploymentId)
                .owner(owner)
                .repo(repo)
                .branch(branch)
                .awsRegion(awsRegion)
                .status("IN_PROGRESS")
                .currentStage(0)  // Stage 0: 시작 전
                .startedAt(LocalDateTime.now())
                .build();

        metadataMap.put(deploymentId, metadata);
        log.info("Deployment metadata initialized: {}", deploymentId);
    }

    // 현재 Stage 업데이트
    public void updateStage(String deploymentId, Integer stage) {
        DeploymentMetadata metadata = metadataMap.get(deploymentId);
        if (metadata != null) {
            metadata.setCurrentStage(stage);
            log.info("Deployment {} stage updated to {}", deploymentId, stage);
        }
    }

    // 배포 완료 (성공)
    public void completeDeployment(String deploymentId, String finalService, String blueUrl, String greenUrl) {
        DeploymentMetadata metadata = metadataMap.get(deploymentId);
        if (metadata != null) {
            metadata.setStatus("COMPLETED");
            metadata.setCurrentStage(6);
            metadata.setCompletedAt(LocalDateTime.now());
            metadata.setFinalService(finalService);
            metadata.setBlueUrl(blueUrl);
            metadata.setGreenUrl(greenUrl);
            log.info("Deployment {} completed successfully", deploymentId);
        }
    }

    // 배포 실패
    public void failDeployment(String deploymentId, String errorMessage) {
        DeploymentMetadata metadata = metadataMap.get(deploymentId);
        if (metadata != null) {
            metadata.setStatus("FAILED");
            metadata.setCompletedAt(LocalDateTime.now());
            metadata.setErrorMessage(errorMessage);
            log.warn("Deployment {} failed: {}", deploymentId, errorMessage);
        }
    }

    // 배포 결과 저장 (배포 완료/실패 시)
    private void saveDeploymentResult(String deploymentId, String status) {
        try {
            DeploymentMetadata metadata = metadataMap.get(deploymentId);

            if (metadata != null) {
                log.info("Deployment result saved - deploymentId: {}, status: {}", deploymentId, status);
            } else {
                log.warn("Failed to save deployment result - metadata not found for deploymentId: {}", deploymentId);
            }
        } catch (Exception e) {
            log.error("Failed to save deployment result for deploymentId: {}", deploymentId, e);
        }
    }

    // 배포 메타데이터 조회 (결과 API용)
    public DeploymentMetadata getMetadata(String deploymentId) {
        return metadataMap.get(deploymentId);
    }

    // 이벤트 히스토리 크기 조회
    public Integer getEventHistorySize(String deploymentId) {
        Queue<DeploymentEvent> events = eventHistoryMap.get(deploymentId);
        return events != null ? events.size() : 0;
    }
}
