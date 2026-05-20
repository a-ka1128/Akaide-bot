package com.akaide.bot.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 인증 관련 엔드포인트.
 *
 * - /api/auth/me : 현재 로그인된 사용자의 discord userId 반환.
 *   프론트가 페이지 로드 시 "로그인 상태인가?" 를 확인할 때 사용.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * 현재 인증된 사용자 정보. JWT가 유효하면 200, 아니면 401.
     * principal 은 JwtAuthenticationFilter 가 세팅한 discord userId(String).
     */
    @GetMapping("/me")
    public ResponseEntity<Map<String, String>> me(@AuthenticationPrincipal String userId,
                                                  Authentication authentication) {
        if (userId == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "authenticated", "true"
        ));
    }
}
