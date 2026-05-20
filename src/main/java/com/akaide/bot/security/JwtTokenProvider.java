package com.akaide.bot.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 발급 / 파싱 / 검증 유틸.
 *
 * Discord OAuth2 로그인 성공 후 사용자 식별자(discord userId)를 subject로 넣어
 * JWT를 발급한다. 이후 모든 API 요청은 이 JWT의 Authorization 헤더로 인증.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    @Value("${app.jwt.secret}")
    private String secretString;

    @Value("${app.jwt.expiration-ms}")
    private long expirationMs;

    private SecretKey signingKey;

    @PostConstruct
    public void init() {
        // HS256 알고리즘은 최소 32바이트 키 필요
        byte[] bytes = secretString.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("app.jwt.secret 은 최소 32바이트(256비트) 이상이어야 합니다.");
        }
        this.signingKey = Keys.hmacShaKeyFor(bytes);
    }

    /** discord userId를 subject로 JWT 발급 */
    public String createToken(String discordUserId, String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(discordUserId)
                .claim("username", username)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    /** JWT 검증. 유효하면 true. 만료/위조 시 false. */
    public boolean validate(String token) {
        try {
            Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token);
            return true;
        } catch (Exception e) {
            log.debug("JWT 검증 실패: {}", e.getMessage());
            return false;
        }
    }

    /** JWT에서 subject(discord userId) 추출 */
    public String getUserId(String token) {
        Claims claims = Jwts.parser().verifyWith(signingKey).build()
                .parseSignedClaims(token).getPayload();
        return claims.getSubject();
    }

    /** JWT에서 username 클레임 추출 (없으면 null) */
    public String getUsername(String token) {
        Claims claims = Jwts.parser().verifyWith(signingKey).build()
                .parseSignedClaims(token).getPayload();
        return claims.get("username", String.class);
    }
}
