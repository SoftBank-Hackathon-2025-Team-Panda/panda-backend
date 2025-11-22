package com.panda.backend.feature.deploy.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CloudWatch 모니터링에 필요한 컨텍스트 정보
 *
 * Step Functions 폴링 중에 수집되는 서비스 정보
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonitoringContext {

    /** Blue 서비스 ARN */
    private String blueServiceArn;

    /** Green 서비스 ARN */
    private String greenServiceArn;

    /** ECS 클러스터명 */
    private String clusterName;

    /** ECS 서비스명 */
    private String serviceName;

    /** Blue URL */
    private String blueUrl;

    /** Green URL */
    private String greenUrl;

    /**
     * 모니터링 준비가 완료되었는지 확인
     */
    public boolean isReadyForMonitoring() {
        return blueServiceArn != null && greenServiceArn != null
            && clusterName != null && serviceName != null;
    }
}
