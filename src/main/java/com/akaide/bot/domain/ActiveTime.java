package com.akaide.bot.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.*;

@Entity @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ActiveTime {
    @Id
    private String dayOfWeek; // "MONDAY", "TUESDAY" ...

    private int startHour;    // 시작 (예: 9)
    private int endHour;      // 종료 (예: 22)
}