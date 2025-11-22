package com.panda.backend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        // 허용할 Origin 목록 (명시적)
        config.addAllowedOrigin("http://localhost:3000");
        config.addAllowedOrigin("http://localhost:3001");
        config.addAllowedOrigin("http://3.34.216.85");
        config.addAllowedOrigin("https://3.34.216.85");
        config.addAllowedOrigin("http://3.34.216.85:3000");
        config.addAllowedOrigin("https://3.34.216.85:3000");

        // 허용할 HTTP 메서드 (명시적)
        config.addAllowedMethod("GET");
        config.addAllowedMethod("POST");
        config.addAllowedMethod("PUT");
        config.addAllowedMethod("DELETE");
        config.addAllowedMethod("PATCH");
        config.addAllowedMethod("OPTIONS");

        // 허용할 요청 헤더 (명시적)
        config.addAllowedHeader("Accept");
        config.addAllowedHeader("Accept-Language");
        config.addAllowedHeader("Content-Language");
        config.addAllowedHeader("Content-Type");
        config.addAllowedHeader("Authorization");
        config.addAllowedHeader("X-API-Key");
        config.addAllowedHeader("X-Requested-With");
        config.addAllowedHeader("X-CSRF-Token");

        // 클라이언트가 응답에서 접근 가능한 헤더
        config.addExposedHeader("Content-Disposition");
        config.addExposedHeader("X-Total-Count");
        config.addExposedHeader("X-Page-Number");
        config.addExposedHeader("Authorization");

        // 자격증명 포함 허용
        config.setAllowCredentials(true);

        // Preflight 요청 캐싱 시간 (1시간)
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    /**
     * WebMvcConfigurer.addCorsMappings() 구현
     * 전역 CORS 설정 (Spring Boot 3.5에서 권장하는 방식)
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
            .allowedOrigins(
                "http://localhost:3000",
                "http://localhost:3001",
                "http://3.34.216.85",
                "https://3.34.216.85",
                "http://3.34.216.85:3000",
                "https://3.34.216.85:3000"
            )
            .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
            .allowedHeaders("Accept", "Accept-Language", "Content-Language", "Content-Type",
                "Authorization", "X-API-Key", "X-Requested-With", "X-CSRF-Token")
            .exposedHeaders("Content-Disposition", "X-Total-Count", "X-Page-Number", "Authorization")
            .allowCredentials(true)
            .maxAge(3600);
    }

}
