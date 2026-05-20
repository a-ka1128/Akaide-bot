package com.akaide.bot.dto;

import com.akaide.bot.domain.Schedule;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Schedule 엔티티를 프론트로 내려보낼 때 사용하는 응답 DTO.
 * userId 같은 내부 식별자는 노출하지 않고, 프론트가 화면 표시에 필요한 필드만 포함.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduleDto {
    private Long id;
    private String task;
    private LocalDateTime targetTime;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private boolean repeat;
    private String repeatRule;
    private boolean alert24h;
    private boolean alert1h;
    private LocalDateTime completedAt;
    private LocalDateTime createdAt;

    public static ScheduleDto from(Schedule s) {
        return ScheduleDto.builder()
                .id(s.getId())
                .task(s.getTask())
                .targetTime(s.getTargetTime())
                .startTime(s.getStartTime())
                .endTime(s.getEndTime())
                .repeat(s.isRepeat())
                .repeatRule(s.getRepeatRule())
                .alert24h(s.isAlert24h())
                .alert1h(s.isAlert1h())
                .completedAt(s.getCompletedAt())
                .createdAt(s.getCreatedAt())
                .build();
    }
}
