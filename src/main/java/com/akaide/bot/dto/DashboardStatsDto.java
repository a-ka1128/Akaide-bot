package com.akaide.bot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 대시보드 홈 화면용 통계 요약 DTO.
 * 여러 API를 호출할 필요 없이 한 번에 받아갈 수 있도록 묶음.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardStatsDto {
    private long totalSchedules;          // 전체 일정 수
    private long completedSchedules;      // 완료된 일정 수
    private long pendingSchedules;        // 미완료 일정 수
    private long todayCount;              // 오늘 일정 수
    private long thisWeekCount;           // 이번 주 일정 수
    private double completionRate;        // 완료율 (0.0 ~ 1.0)

    /** 요일별 일정 개수 (예: "MONDAY" -> 3) */
    private Map<String, Long> byDayOfWeek;

    /** 시간대별 일정 개수 (0~23시) */
    private Map<Integer, Long> byHour;
}
