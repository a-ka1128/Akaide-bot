package com.akaide.bot.dto;

import com.akaide.bot.domain.ScheduleCategory;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * POST /api/schedules 요청 바디 (폼 직접 입력).
 */
@Getter
@Setter
@NoArgsConstructor
public class CreateScheduleRequest {
    private String task;
    private LocalDateTime targetTime;
    private LocalDateTime endTime;     // 선택
    private boolean alert24h;
    private boolean alert1h;
    private ScheduleCategory category; // 선택 — null 이면 서비스가 OTHER 로 처리
}
