package com.panda.backend.feature.deploy.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.panda.backend.feature.deploy.dto.RegisterEventBusRequest;
import com.panda.backend.feature.deploy.dto.RegisterEventBusResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.lambda.LambdaClient;
import software.amazon.awssdk.services.lambda.model.InvokeRequest;
import software.amazon.awssdk.services.lambda.model.InvokeResponse;

import java.nio.charset.StandardCharsets;

/**
 * 서비스 계정의 Lambda 함수를 호출하는 서비스
 *
 * 용도:
 * 1. lambda_0_register_to_eventbus 호출
 *    → 사용자 계정이 서비스 계정의 Event Bus로 이벤트를 보낼 수 있도록 권한 설정
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LambdaInvocationService {

    private final LambdaClient lambdaClient;
    private final ObjectMapper objectMapper;

    @Value("${aws.lambda.register-eventbus-function-name:lambda_0_register_to_eventbus}")
    private String registerEventBusLambdaName;

    /**
     * 서비스 계정의 Lambda를 호출하여 Event Bus 권한 설정 요청
     *
     * @param request 사용자 AWS 정보 (Access Key, Secret Key, Region)
     * @return Lambda 호출 응답
     * @throws RuntimeException Lambda 호출 실패 시
     */
    public RegisterEventBusResponse invokeRegisterEventBusLambda(RegisterEventBusRequest request) {
        try {
            // 요청을 JSON으로 직렬화
            String payload = objectMapper.writeValueAsString(request);

            log.info("Invoking lambda function: {} with region: {}",
                registerEventBusLambdaName, request.getRegion());
            log.debug("Lambda invocation payload: {}", payload);

            // Lambda 호출 요청 생성
            InvokeRequest invokeRequest = InvokeRequest.builder()
                .functionName(registerEventBusLambdaName)
                .invocationType("RequestResponse")  // 동기 호출 (응답 대기)
                .payload(SdkBytes.fromString(payload, StandardCharsets.UTF_8))
                .build();

            // Lambda 호출
            InvokeResponse invokeResponse = lambdaClient.invoke(invokeRequest);

            // 응답 파싱
            String responseBody = invokeResponse.payload().asUtf8String();

            log.debug("Lambda response status code: {}", invokeResponse.statusCode());
            log.debug("Lambda response body: {}", responseBody);

            // JSON 응답을 DTO로 변환
            RegisterEventBusResponse response = objectMapper.readValue(
                responseBody,
                RegisterEventBusResponse.class
            );

            // 상태 확인
            if (response.isSuccess()) {
                log.info("Event Bus permission registered successfully - principal: {}",
                    response.getPrincipal());
            } else {
                log.warn("Event Bus permission registration failed - message: {}",
                    response.getMessage());
            }

            return response;

        } catch (Exception e) {
            log.error("Failed to invoke lambda function: {}", registerEventBusLambdaName, e);
            throw new RuntimeException("Failed to invoke Lambda: " + e.getMessage(), e);
        }
    }

    /**
     * Lambda 호출 응답의 상태를 확인하고 에러이면 예외 발생
     *
     * @param response Lambda 호출 응답
     * @throws RuntimeException 응답 상태가 OK가 아닐 경우
     */
    public void validateRegisterEventBusResponse(RegisterEventBusResponse response) {
        if (response == null) {
            throw new RuntimeException("Lambda response is null");
        }

        if (response.isFailure()) {
            String errorMsg = String.format("Event Bus permission registration failed: %s",
                response.getMessage());
            log.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }

        if (!response.isSuccess()) {
            String errorMsg = String.format("Unexpected Lambda response status: %s, message: %s",
                response.getStatus(), response.getMessage());
            log.error(errorMsg);
            throw new RuntimeException(errorMsg);
        }
    }
}
