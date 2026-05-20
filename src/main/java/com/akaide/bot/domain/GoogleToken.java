package com.akaide.bot.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class GoogleToken {

    @Id
    private String userId; // 디스코드 유저 ID (Primary Key)

    @Column(length = 2000) // 액세스 토큰은 길 수 있으므로 넉넉하게 설정
    private String accessToken;

    @Column(length = 2000)
    private String refreshToken;

    private Long expirationTimeMillis; // 토큰 만료 시간

    private LocalDateTime updatedAt;

    // 만료 여부 확인 로직
    public boolean isExpired() {
        return System.currentTimeMillis() > (expirationTimeMillis - 60000); // 1분 여유를 둠
    }
}