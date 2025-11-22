package com.panda.backend.feature.deploy.application;

import com.panda.backend.feature.deploy.dto.DeploymentResult;
import com.panda.backend.feature.deploy.infrastructure.DeploymentResultStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 배포 결과를 조회하는 서비스
 *
 * - deploymentId로 결과 조회
 * - 완료된 배포만 결과 반환
 * - API 스펙에 따라 정확한 데이터 반환
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GetDeploymentResultService {

    private final DeploymentResultStore deploymentResultStore;

    /**
     * deploymentId로 배포 결과 조회
     *
     * @param deploymentId 배포 ID
     * @return 배포 결과
     * @throws IllegalArgumentException 결과를 찾을 수 없을 때
     */
    public DeploymentResult getResult(String deploymentId) {
        if (deploymentId == null || deploymentId.isEmpty()) {
            log.warn("Invalid deploymentId provided");
            throw new IllegalArgumentException("배포 ID는 필수입니다");
        }

        DeploymentResult result = deploymentResultStore.get(deploymentId);

        if (result == null) {
            log.warn("Deployment result not found - deploymentId: {}", deploymentId);
            throw new IllegalArgumentException("배포 결과를 찾을 수 없습니다: " + deploymentId);
        }

        log.info("Deployment result retrieved - deploymentId: {}, status: {}", deploymentId, result.getStatus());
        return result;
    }

    /**
     * deploymentId로 배포 결과 조회 (Optional)
     *
     * @param deploymentId 배포 ID
     * @return 배포 결과 (없으면 null)
     */
    public DeploymentResult getResultOrNull(String deploymentId) {
        if (deploymentId == null || deploymentId.isEmpty()) {
            return null;
        }
        return deploymentResultStore.get(deploymentId);
    }

    /**
     * 배포가 완료되었는지 확인
     *
     * @param deploymentId 배포 ID
     * @return 완료 여부
     */
    public boolean isCompleted(String deploymentId) {
        DeploymentResult result = getResultOrNull(deploymentId);
        return result != null && result.isCompleted();
    }

    /**
     * 배포 준비가 완료되었는지 확인 (전환 대기 중)
     *
     * @param deploymentId 배포 ID
     * @return 배포 준비 완료 여부
     */
    public boolean isDeploymentReady(String deploymentId) {
        DeploymentResult result = getResultOrNull(deploymentId);
        return result != null && result.isDeploymentReady();
    }

    /**
     * 배포 성공 여부 확인
     *
     * @param deploymentId 배포 ID
     * @return 성공 여부
     */
    public boolean isSuccessful(String deploymentId) {
        DeploymentResult result = getResultOrNull(deploymentId);
        return result != null && result.isSuccessful();
    }

    /**
     * 배포 실패 여부 확인
     *
     * @param deploymentId 배포 ID
     * @return 실패 여부
     */
    public boolean isFailed(String deploymentId) {
        DeploymentResult result = getResultOrNull(deploymentId);
        return result != null && result.isFailed();
    }
}
