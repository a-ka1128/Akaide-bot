package com.akaide.bot.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

/**
 * Discord OAuth2 로그인 성공 후 호출되는 핸들러.
 *
 * 1. OAuth2User 에서 Discord userId, username 추출
 * 2. JwtTokenProvider 로 JWT 발급
 * 3. 프론트엔드 콜백 URL로 리다이렉트 (쿼리 파라미터에 토큰 포함)
 *
 * 보안 노트: 운영 환경에서는 토큰을 쿼리 파라미터 대신
 * HttpOnly 쿠키 등 더 안전한 방식으로 전달하는 것이 좋다.
 * 개발 편의성을 위해 일단 쿼리로 전달.
 */
@Slf4j
@Component
public class OAuth2SuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final JwtTokenProvider jwtTokenProvider;

    @Value("${app.frontend.redirect-uri}")
    private String frontendRedirectUri;

    public OAuth2SuccessHandler(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request,
                                        HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        OAuth2User oauth2User = (OAuth2User) authentication.getPrincipal();

        // Discord user-info-uri 응답 예:
        // {"id":"343290913172226049","username":"akaide", ...}
        String discordUserId = (String) oauth2User.getAttributes().get("id");
        String username = (String) oauth2User.getAttributes().get("username");

        if (discordUserId == null) {
            log.error("Discord OAuth2 응답에 id 가 없습니다. attrs={}", oauth2User.getAttributes());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid OAuth2 response");
            return;
        }

        String jwt = jwtTokenProvider.createToken(discordUserId, username);

        String redirectUrl = UriComponentsBuilder.fromUriString(frontendRedirectUri)
                .queryParam("token", jwt)
                .build().toUriString();

        log.info("✅ Discord 로그인 성공: userId={}, username={}", discordUserId, username);
        getRedirectStrategy().sendRedirect(request, response, redirectUrl);
    }
}
