package com.panda.backend.feature.deploy.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * CloudWatch 메트릭 모니터링 Lambda 호출 요청
 *
 * Lambda 함수: lambda_1_monitor_cloudwatch
 * 용도: Blue/Green 서비스의 CloudWatch 메트릭 수집
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MonitorCloudWatchRequest {

    /** 배포 ID */
    private String deploymentId;

    /** Blue 서비스 ARN */
    private String blueServiceArn;

    /** Green 서비스 ARN */
    private String greenServiceArn;

    /** 클러스터명 */
    private String clusterName;

    /** 서비스명 */
    private String serviceName;

    /** 메트릭 수집 범위 (분) - 기본값: 5 */
    private Integer minutesRange;

    /** 통계 타입 - Average, Maximum, Minimum, Sum */
    private String statisticType;
}
