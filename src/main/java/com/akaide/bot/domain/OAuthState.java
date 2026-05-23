package com.akaide.bot.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Google OAuth2 CSRF 방지용 state 토큰.
 *
 * /구글연동 시 랜덤 state 를 발급해 저장하고, 콜백에서 이 값을 대조한다.
 * 검증에 성공하면 1회용으로 삭제하여 재사용을 막는다.
 */
@Entity
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OAuthState {
    @Id
    private String state;          // 랜덤 UUID

    private String discordUserId;  // 이 state 가 묶일 Discord userId

    private LocalDateTime createdAt; // 만료(cleanup) 기준
}
