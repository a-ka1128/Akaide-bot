package com.akaide.bot.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Spring Security 설정.
 *
 * 규칙:
 * - /api/** 는 JWT 인증 필수
 * - /oauth2/**, /login/** 은 OAuth2 로그인 흐름을 위해 모두 허용
 * - 정적 리소스(/, /index.html, /assets/**)는 모두 허용 (React 빌드 결과 서빙용)
 * - 세션은 사용하지 않음 (Stateless JWT)
 * - 봇이 사용하는 Google OAuth2 콜백(/api/oauth2/callback)도 그대로 허용 (인증 안 거치게)
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final OAuth2SuccessHandler oAuth2SuccessHandler;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          OAuth2SuccessHandler oAuth2SuccessHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.oAuth2SuccessHandler = oAuth2SuccessHandler;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CSRF는 stateless API에선 보통 끈다 (JWT가 대신 보호)
                .csrf(AbstractHttpConfigurer::disable)
                // CORS는 WebConfig에서 처리 (Spring Security가 이를 인지하도록 활성화만)
                .cors(cors -> {})
                // 세션 사용 안 함
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 사전 요청(CORS preflight) 허용
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()

                        // OAuth2 로그인 진입/콜백 경로 모두 허용
                        .requestMatchers("/oauth2/**", "/login/**").permitAll()

                        // 정적 리소스 + SPA 라우트 (React 빌드 결과)
                        .requestMatchers(
                                "/", "/index.html",
                                "/assets/**", "/static/**",
                                "/favicon.ico", "/favicon.svg",
                                "/*.svg", "/*.png", "/*.ico", "/*.txt", "/*.json",
                                // SpaController가 forward 시키는 React 라우트들
                                "/calendar", "/school", "/free-time", "/settings", "/oauth/callback"
                        ).permitAll()

                        // 봇이 쓰는 Google OAuth2 콜백은 인증 없이 열어둠
                        .requestMatchers("/api/oauth2/callback").permitAll()

                        // 인증 API (/api/auth/**)도 일부는 공개 (예: /api/auth/me 는 인증 필요)
                        // → /api/auth/me 는 JWT가 없으면 401 떨어지도록 아래 anyRequest 규칙에 위임

                        // 그 외 /api/** 는 모두 인증 필요
                        .requestMatchers("/api/**").authenticated()

                        // 나머지는 일단 허용 (정적 리소스 catch-all)
                        .anyRequest().permitAll()
                )
                // Discord OAuth2 로그인 활성화
                .oauth2Login(oauth -> oauth.successHandler(oAuth2SuccessHandler))
                // /api/** 경로는 인증 실패 시 OAuth2 로그인 페이지로 리다이렉트하지 않고
                // 깔끔하게 401 JSON 응답을 돌려준다. (프론트의 fetch 가 처리하기 편함)
                .exceptionHandling(eh -> eh.defaultAuthenticationEntryPointFor(
                        new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        PathPatternRequestMatcher.withDefaults().matcher("/api/**")
                ))
                // 우리가 만든 JWT 필터를 Spring 표준 인증 필터 앞에 끼워넣기
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
