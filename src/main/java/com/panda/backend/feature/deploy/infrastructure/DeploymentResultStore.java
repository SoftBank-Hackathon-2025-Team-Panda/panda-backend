package com.panda.backend.feature.deploy.infrastructure;

import com.panda.backend.feature.deploy.dto.DeploymentResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 배포 결과를 메모리에 저장하고 조회하는 저장소
 * - 최대 1000개 결과 보관
 * - 초과 시 가장 오래된 것부터 삭제
 * - 스레드 안전성 보장
 */
@Slf4j
@Component
public class DeploymentResultStore {

    private static final int MAX_RESULTS = 1000;
    private final Map<String, DeploymentResult> results = new ConcurrentHashMap<>();
    private final LinkedHashMap<String, Long> insertionOrder = new LinkedHashMap<>();

    /**
     * 배포 결과 저장
     *
     * @param result 저장할 배포 결과
     */
    public void save(DeploymentResult result) {
        if (result == null || result.getDeploymentId() == null) {
            log.warn("Cannot save null or invalid deployment result");
            return;
        }

        String deploymentId = result.getDeploymentId();

        // 기존 결과면 삭제 (최신 상태로 업데이트)
        if (results.containsKey(deploymentId)) {
            insertionOrder.remove(deploymentId);
            log.debug("Updating existing result for deploymentId: {}", deploymentId);
        }

        // 새로운 결과 저장
        results.put(deploymentId, result);
        insertionOrder.put(deploymentId, System.currentTimeMillis());

        log.info("Deployment result saved - deploymentId: {}, status: {}", deploymentId, result.getStatus());

        // 최대 개수 초과 시 가장 오래된 것 삭제
        if (results.size() > MAX_RESULTS) {
            evictOldest();
        }
    }

    /**
     * 배포 ID로 결과 조회
     *
     * @param deploymentId 배포 ID
     * @return 배포 결과 (없으면 null)
     */
    public DeploymentResult get(String deploymentId) {
        DeploymentResult result = results.get(deploymentId);
        if (result != null) {
            log.debug("Retrieved deployment result - deploymentId: {}, status: {}", deploymentId, result.getStatus());
        } else {
            log.debug("Deployment result not found - deploymentId: {}", deploymentId);
        }
        return result;
    }

    /**
     * 모든 배포 결과 조회
     *
     * @return 배포 결과 리스트
     */
    public List<DeploymentResult> getAll() {
        return new ArrayList<>(results.values());
    }

    /**
     * 배포 결과 삭제
     *
     * @param deploymentId 배포 ID
     */
    public void delete(String deploymentId) {
        if (results.remove(deploymentId) != null) {
            insertionOrder.remove(deploymentId);
            log.info("Deployment result deleted - deploymentId: {}", deploymentId);
        }
    }

    /**
     * 모든 배포 결과 삭제 (테스트용)
     */
    public void clear() {
        results.clear();
        insertionOrder.clear();
        log.info("All deployment results cleared");
    }

    /**
     * 저장된 결과 개수
     *
     * @return 결과 개수
     */
    public int size() {
        return results.size();
    }

    /**
     * 최신 N개 결과 조회
     *
     * @param limit 조회할 최대 개수
     * @return 최신 배포 결과 리스트
     */
    public List<DeploymentResult> getLatest(int limit) {
        return results.values().stream()
            .sorted((a, b) -> {
                if (a.getCompletedAt() != null && b.getCompletedAt() != null) {
                    return b.getCompletedAt().compareTo(a.getCompletedAt());
                }
                return 0;
            })
            .limit(limit)
            .toList();
    }

    /**
     * 가장 오래된 결과 삭제
     */
    private synchronized void evictOldest() {
        if (insertionOrder.isEmpty()) {
            return;
        }

        // LinkedHashMap의 첫 번째 항목(가장 오래된 것)을 가져옴
        String oldestDeploymentId = insertionOrder.keySet().iterator().next();
        results.remove(oldestDeploymentId);
        insertionOrder.remove(oldestDeploymentId);

        log.info("Evicted oldest deployment result due to size limit - deploymentId: {}", oldestDeploymentId);
    }

    /**
     * 상태별 결과 조회
     *
     * @param status 배포 상태 (COMPLETED, FAILED)
     * @return 해당 상태의 배포 결과 리스트
     */
    public List<DeploymentResult> getByStatus(String status) {
        return results.values().stream()
            .filter(r -> status.equals(r.getStatus()))
            .toList();
    }
}
