package com.panda.backend.feature.deploy.exception;

import org.springframework.http.HttpStatus;

/**
 * 배포 프로세스에서 발생하는 에러 코드 및 HTTP 상태 맵핑
 *
 * API_SPECIFICATION.md의 에러 코드 목록과 일치
 */
public enum ErrorCode {

    /**
     * 408 Request Timeout
     * 배포 타임아웃 (단계 또는 전체)
     */
    DEPLOYMENT_TIMEOUT("DEPLOYMENT_TIMEOUT", HttpStatus.REQUEST_TIMEOUT, "배포 타임아웃"),

    /**
     * 400 Bad Request
     * Stage 1: Docker 빌드 실패
     */
    DOCKER_BUILD_FAILED("DOCKER_BUILD_FAILED", HttpStatus.BAD_REQUEST, "Docker 빌드 실패"),

    /**
     * 400 Bad Request
     * Stage 3: ECS 배포 실패
     */
    ECS_DEPLOYMENT_FAILED("ECS_DEPLOYMENT_FAILED", HttpStatus.BAD_REQUEST, "ECS 배포 실패"),

    /**
     * 400 Bad Request
     * Stage 5: 헬스체크 실패
     */
    HEALTH_CHECK_FAILED("HEALTH_CHECK_FAILED", HttpStatus.BAD_REQUEST, "헬스체크 실패"),

    /**
     * 500 Internal Server Error
     * 예상하지 못한 오류
     */
    UNEXPECTED_ERROR("UNEXPECTED_ERROR", HttpStatus.INTERNAL_SERVER_ERROR, "예상 외 에러");

    private final String code;
    private final HttpStatus httpStatus;
    private final String description;

    ErrorCode(String code, HttpStatus httpStatus, String description) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getHttpStatus() {
        return httpStatus;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 에러 코드 문자열로부터 enum 조회
     *
     * @param code 에러 코드 문자열
     * @return ErrorCode enum (없으면 UNEXPECTED_ERROR)
     */
    public static ErrorCode fromCode(String code) {
        if (code == null) {
            return UNEXPECTED_ERROR;
        }
        try {
            return ErrorCode.valueOf(code);
        } catch (IllegalArgumentException e) {
            return UNEXPECTED_ERROR;
        }
    }
}
