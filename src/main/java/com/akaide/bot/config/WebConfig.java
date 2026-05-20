package com.akaide.bot.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * 웹 대시보드용 CORS 설정.
 *
 * 허용 origin 은 application-*.yml 의 app.cors.allowed-origins 에서 읽는다.
 *   - 개발(local): http://localhost:5173,http://localhost:3000
 *   - 운영(prod):  https://<firebase>.web.app 등 (환경변수 CORS_ALLOWED_ORIGINS)
 *
 * Spring Security 와 함께 쓸 때는 CorsConfigurationSource 빈을 등록하는 것이 안전하다.
 * SecurityFilterChain.cors(...) 가 자동으로 이 빈을 사용한다.
 */
@Configuration
public class WebConfig {

    @Value("${app.cors.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    @Bean
    public UrlBasedCorsConfigurationSource corsConfigurationSource() {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        source.registerCorsConfiguration("/oauth2/**", config);
        source.registerCorsConfiguration("/login/**", config);
        return source;
    }
}
