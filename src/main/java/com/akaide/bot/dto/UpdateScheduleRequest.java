package com.akaide.bot.dto;

import com.akaide.bot.domain.ScheduleCategory;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * PATCH /api/schedules/{id} 요청 바디.
 * 변경하지 않을 필드는 null/미포함으로 보낸다.
 *
 * 캘린더 드래그/리사이즈에서는 targetTime / endTime만,
 * 모달 수정에서는 task / alert24h / alert1h / category 등이 함께 올 수 있다.
 */
@Getter
@Setter
@NoArgsConstructor
public class UpdateScheduleRequest {
    private String task;
    private LocalDateTime targetTime;
    private LocalDateTime endTime;
    private Boolean alert24h;
    private Boolean alert1h;
    private ScheduleCategory category;
}
