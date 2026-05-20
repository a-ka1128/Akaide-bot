package com.akaide.bot.controller;

import com.akaide.bot.domain.GoogleToken;
import com.akaide.bot.repository.GoogleTokenRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;

@RestController // 👈 디스코드가 아닌 '웹 브라우저'의 요청을 받는 클래스임을 선언
public class OAuth2Controller {

    private final GoogleTokenRepository googleTokenRepository;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${google.client.id}")
    private String clientId;

    @Value("${google.client.secret}")
    private String clientSecret;

    @Value("${google.redirect.uri:http://localhost:8080/api/oauth2/callback}")
    private String redirectUri;

    public OAuth2Controller(GoogleTokenRepository googleTokenRepository) {
        this.googleTokenRepository = googleTokenRepository;
    }

    // 구글 로그인이 끝나면 구글이 이 주소로 사용자를 리다이렉트 시킵니다.
    @GetMapping("/api/oauth2/callback")
    public String googleAuthCallback(
            @RequestParam("code") String code,   // 구글이 주는 임시 인증 코드
            @RequestParam("state") String userId // 우리가 몰래 보냈던 디스코드 유저 ID
    ) {
        try {
            // 1. 구글에 '인증 코드'를 주고 진짜 '토큰'으로 교환해달라고 요청을 만듭니다.
            String tokenUrl = "https://oauth2.googleapis.com/token";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("code", code);
            params.add("client_id", clientId);
            params.add("client_secret", clientSecret);
            params.add("redirect_uri", redirectUri);
            params.add("grant_type", "authorization_code");

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);

            // 2. 구글 서버로 토큰 교환 요청 쏘기!
            ResponseEntity<String> response = restTemplate.postForEntity(tokenUrl, request, String.class);
            JsonNode rootNode = objectMapper.readTree(response.getBody());

            String accessToken = rootNode.path("access_token").asText();
            Long expiresIn = rootNode.path("expires_in").asLong();

            // 리프레시 토큰은 최초 로그인 시(또는 prompt=consent 일 때)에만 발급됩니다.
            String refreshToken = rootNode.has("refresh_token") ? rootNode.path("refresh_token").asText() : null;

            // 3. DB에서 해당 유저의 기존 토큰 정보 가져오기 (없으면 새로 생성)
            GoogleToken googleToken = googleTokenRepository.findByUserId(userId)
                    .orElse(GoogleToken.builder().userId(userId).build());

            // 4. 새로운 토큰 값으로 업데이트
            googleToken.setAccessToken(accessToken);
            if (refreshToken != null) {
                googleToken.setRefreshToken(refreshToken);
            }
            googleToken.setExpirationTimeMillis(System.currentTimeMillis() + (expiresIn * 1000));
            googleToken.setUpdatedAt(LocalDateTime.now());

            // 5. DB에 저장
            googleTokenRepository.save(googleToken);

            // 6. 사용자의 웹 브라우저에 보여줄 성공 메시지
            return "<html><body>" +
                    "<h1>✅ Akaide 봇과 구글 캘린더 연동이 완료되었습니다!</h1>" +
                    "<p>이제 이 창을 닫고 디스코드로 돌아가서 봇을 사용해보세요.</p>" +
                    "</body></html>";

        } catch (Exception e) {
            e.printStackTrace();
            return "<html><body><h1>❌ 인증 중 오류가 발생했습니다.</h1><p>" + e.getMessage() + "</p></body></html>";
        }
    }
}