package com.akaide.bot.service;

import com.akaide.bot.domain.GoogleToken;
import com.akaide.bot.repository.GoogleTokenRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.auth.oauth2.BearerToken;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;

/**
 * 구글 캘린더 연동 서비스 (멀티 유저 지원 버전)
 *
 * 각 디스코드 유저의 OAuth2 토큰(GoogleToken)을 DB에서 꺼내
 * 유저별 Calendar 객체를 동적으로 빌드해서 사용합니다.
 */
@Slf4j
@Service
public class GoogleCalendarService {

    private static final String APPLICATION_NAME = "Akaide Bot";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();
    private static final String GOOGLE_TOKEN_URL = "https://oauth2.googleapis.com/token";

    private final GoogleTokenRepository googleTokenRepository;
    private final NetHttpTransport httpTransport;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${google.client.id}")
    private String clientId;

    @Value("${google.client.secret}")
    private String clientSecret;

    public GoogleCalendarService(GoogleTokenRepository googleTokenRepository) throws GeneralSecurityException, IOException {
        this.googleTokenRepository = googleTokenRepository;
        this.httpTransport = GoogleNetHttpTransport.newTrustedTransport();
        log.info("✅ 구글 캘린더 서비스 준비 완료 (멀티 유저 모드)");
    }

    // =================================================================
    // 🔐 유저별 Calendar 객체 빌더
    // =================================================================

    /**
     * 특정 디스코드 유저의 Calendar 객체를 빌드합니다.
     * 토큰이 없거나 만료됐으면 자동 갱신을 시도하고, 그래도 실패하면 예외를 던집니다.
     */
    @Transactional
    public Calendar getCalendarForUser(String userId) throws UserNotAuthenticatedException {
        GoogleToken token = googleTokenRepository.findByUserId(userId)
                .orElseThrow(() -> new UserNotAuthenticatedException(
                        "유저 " + userId + "는 아직 구글 계정을 연동하지 않았습니다. /구글연동 명령어로 로그인해주세요."));

        // 만료 시 Refresh
        if (token.isExpired()) {
            log.info("🔄 유저 {}의 Access Token이 만료되어 Refresh를 시도합니다.", userId);
            token = refreshAccessToken(token);
        }

        Credential credential = new Credential.Builder(BearerToken.authorizationHeaderAccessMethod())
                .build()
                .setAccessToken(token.getAccessToken());

        return new Calendar.Builder(httpTransport, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();
    }

    /**
     * Refresh Token을 사용해 새로운 Access Token을 발급받고 DB에 저장합니다.
     *
     * 방어 로직:
     *  - refresh_token이 비어있으면 즉시 재인증 요청
     *  - 응답이 비어있거나 access_token 누락 시 명확한 메시지로 예외 발생
     *  - expires_in 누락 시 보수적으로 1시간(3600초) 기본값 적용
     *  - error/error_description 필드가 있으면 로그에 상세 기록 (invalid_grant 등)
     */
    private GoogleToken refreshAccessToken(GoogleToken token) throws UserNotAuthenticatedException {
        if (token.getRefreshToken() == null || token.getRefreshToken().isBlank()) {
            throw new UserNotAuthenticatedException(
                    "Refresh Token이 없어 재인증이 필요합니다. /구글연동 명령어로 다시 로그인해주세요.");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("client_id", clientId);
            params.add("client_secret", clientSecret);
            params.add("refresh_token", token.getRefreshToken());
            params.add("grant_type", "refresh_token");

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(GOOGLE_TOKEN_URL, request, String.class);

            String body = response.getBody();
            if (body == null || body.isBlank()) {
                throw new UserNotAuthenticatedException("토큰 갱신 응답이 비어 있습니다. /구글연동으로 다시 로그인해주세요.");
            }

            JsonNode node;
            try {
                node = objectMapper.readTree(body);
            } catch (Exception parseEx) {
                log.error("🔴 토큰 갱신 응답 파싱 실패 (유저: {}). raw={}", token.getUserId(), body, parseEx);
                throw new UserNotAuthenticatedException("토큰 갱신 응답 파싱 실패. /구글연동으로 다시 로그인해주세요.");
            }

            // OAuth 에러 응답 (invalid_grant, invalid_client 등)
            if (node.hasNonNull("error")) {
                String err = node.path("error").asText();
                String desc = node.path("error_description").asText("(no description)");
                log.error("🔴 Google OAuth 에러 (유저: {}): {} - {}", token.getUserId(), err, desc);
                throw new UserNotAuthenticatedException(
                        "구글 인증이 만료/취소되었습니다 (" + err + "). /구글연동으로 다시 로그인해주세요.");
            }

            if (!node.hasNonNull("access_token")) {
                log.error("🔴 access_token 필드 누락 (유저: {}). body={}", token.getUserId(), body);
                throw new UserNotAuthenticatedException("access_token이 응답에 없습니다. /구글연동으로 다시 로그인해주세요.");
            }

            String newAccessToken = node.path("access_token").asText();
            // expires_in 누락 시 보수적으로 1시간(3600초) 기본값 사용
            long expiresIn = node.hasNonNull("expires_in") ? node.path("expires_in").asLong(3600L) : 3600L;

            token.setAccessToken(newAccessToken);
            token.setExpirationTimeMillis(System.currentTimeMillis() + (expiresIn * 1000));
            token.setUpdatedAt(LocalDateTime.now());

            // refresh_token이 응답에 새로 포함됐다면 갱신 (구글은 보통 재발급 안 함)
            if (node.hasNonNull("refresh_token")) {
                String newRefresh = node.path("refresh_token").asText();
                if (!newRefresh.isBlank()) {
                    token.setRefreshToken(newRefresh);
                }
            }

            googleTokenRepository.save(token);
            log.info("✅ 유저 {}의 Access Token이 성공적으로 갱신되었습니다. (만료 {}초 후)",
                    token.getUserId(), expiresIn);
            return token;

        } catch (UserNotAuthenticatedException e) {
            throw e; // 이미 의미 있는 메시지를 가진 예외는 그대로 전파
        } catch (Exception e) {
            log.error("🔴 Refresh Token 갱신 실패 (유저: {})", token.getUserId(), e);
            throw new UserNotAuthenticatedException(
                    "토큰 갱신에 실패했습니다. /구글연동 명령어로 다시 로그인해주세요.");
        }
    }

    // =================================================================
    // 📅 Calendar API 래퍼 메서드 (유저별)
    // =================================================================

    /**
     * 특정 유저의 캘린더에 일정을 등록합니다.
     */
    public void addEvent(String userId, String summary, LocalDateTime startDateTime, LocalDateTime endDateTime) {
        try {
            Calendar service = getCalendarForUser(userId);

            Event event = new Event().setSummary(summary);

            DateTime start = new DateTime(startDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            event.setStart(new EventDateTime().setDateTime(start));

            if (endDateTime == null) endDateTime = startDateTime.plusHours(1);
            DateTime end = new DateTime(endDateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            event.setEnd(new EventDateTime().setDateTime(end));

            service.events().insert("primary", event).execute();
            log.info("📅 [유저 {}] 구글 캘린더 일정 등록 완료: {}", userId, summary);

        } catch (UserNotAuthenticatedException e) {
            log.warn("⚠️ [유저 {}] 구글 미연동 상태라 캘린더 등록을 건너뜁니다: {}", userId, e.getMessage());
        } catch (IOException e) {
            log.error("🔴 [유저 {}] 구글 캘린더 API 호출 실패", userId, e);
        }
    }

    /**
     * 특정 유저의 특정 날짜 일정 목록을 가져옵니다.
     * 토큰이 없는 유저는 빈 리스트를 반환합니다 (가용시간 분석 시에도 봇이 멈추지 않도록).
     */
    public List<Event> getEventsForDate(String userId, LocalDateTime date) {
        try {
            Calendar service = getCalendarForUser(userId);

            DateTime startOfDay = new DateTime(date.with(LocalTime.MIN).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            DateTime endOfDay = new DateTime(date.with(LocalTime.MAX).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());

            return service.events().list("primary")
                    .setTimeMin(startOfDay)
                    .setTimeMax(endOfDay)
                    .setOrderBy("startTime")
                    .setSingleEvents(true)
                    .execute()
                    .getItems();

        } catch (UserNotAuthenticatedException e) {
            log.warn("⚠️ [유저 {}] 구글 미연동 상태. 구글 일정은 제외하고 분석합니다.", userId);
            return Collections.emptyList();
        } catch (IOException e) {
            log.error("🔴 [유저 {}] 구글 캘린더 조회 실패", userId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 해당 유저가 구글 계정을 연동했는지 여부를 확인합니다.
     */
    public boolean isUserAuthenticated(String userId) {
        return googleTokenRepository.findByUserId(userId).isPresent();
    }

    // =================================================================
    // 💥 커스텀 예외
    // =================================================================

    public static class UserNotAuthenticatedException extends Exception {
        public UserNotAuthenticatedException(String message) {
            super(message);
        }
    }
}
