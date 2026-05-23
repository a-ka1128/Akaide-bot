package com.akaide.bot.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import lombok.*;

/**
 * 사용자별 요일 활동 시간 (= 가용 시간 분석 기준).
 *
 * 복합키 (userId + dayOfWeek) 로 사용자마다 독립적인 활동 시간을 가진다.
 */
@Entity @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@IdClass(ActiveTimeId.class)
public class ActiveTime {
    @Id
    private String userId;     // Discord userId

    @Id
    private String dayOfWeek;  // "MONDAY", "TUESDAY" ...

    private int startHour;     // 시작 (예: 9)
    private int endHour;       // 종료 (예: 22)
}
