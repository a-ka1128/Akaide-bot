package com.akaide.bot.service;

import com.akaide.bot.domain.OAuthState;
import com.akaide.bot.repository.OAuthStateRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class OAuth2Service {

    @Value("${google.client.id}")
    private String clientId;

    @Value("${google.redirect.uri:http://localhost:8080/api/oauth2/callback}")
    private String redirectUri;

    private final OAuthStateRepository oAuthStateRepository;

    public OAuth2Service(OAuthStateRepository oAuthStateRepository) {
        this.oAuthStateRepository = oAuthStateRepository;
    }

    /**
     * Google OAuth2 인증 URL 생성.
     *
     * state 에 Discord userId 를 그대로 노출하던 기존 방식은 CSRF 에 취약했다.
     * (공격자가 피해자의 Discord ID 를 state 에 넣어 콜백을 유도하면
     *  자기 Google 계정을 피해자에게 묶을 수 있었음)
     *
     * 이제는 추측 불가능한 랜덤 state 를 발급해 DB 에 저장하고,
     * 콜백에서 이 state 로 실제 userId 를 역조회 + 1회용 소비한다.
     */
    public String getAuthorizationUrl(String discordUserId) {
        String state = UUID.randomUUID().toString();
        oAuthStateRepository.save(OAuthState.builder()
                .state(state)
                .discordUserId(discordUserId)
                .createdAt(LocalDateTime.now())
                .build());

        String scope = "https://www.googleapis.com/auth/calendar";
        String encodedScope = URLEncoder.encode(scope, StandardCharsets.UTF_8);
        String encodedRedirect = URLEncoder.encode(redirectUri, StandardCharsets.UTF_8);

        return "https://accounts.google.com/o/oauth2/v2/auth?" +
                "client_id=" + clientId +
                "&redirect_uri=" + encodedRedirect +
                "&response_type=code" +
                "&scope=" + encodedScope +
                "&access_type=offline" +
                "&prompt=consent" +
                "&state=" + state;
    }
}
