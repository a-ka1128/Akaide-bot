package com.akaide.bot.controller;

import com.akaide.bot.domain.Schedule;
import com.akaide.bot.dto.DashboardStatsDto;
import com.akaide.bot.repository.ScheduleRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 대시보드 홈에 표시할 통계 요약 API.
 * 본인 데이터 기준으로 통계 산출.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final ScheduleRepository scheduleRepository;

    public DashboardController(ScheduleRepository scheduleRepository) {
        this.scheduleRepository = scheduleRepository;
    }

    /** GET /api/dashboard/stats — 본인 일정 기준 통계 요약 */
    @GetMapping("/stats")
    public DashboardStatsDto getStats(@AuthenticationPrincipal String userId) {
        List<Schedule> all = scheduleRepository.findAllByUserId(userId);

        long total = all.size();
        long completed = all.stream().filter(s -> s.getCompletedAt() != null).count();
        long pending = total - completed;
        double rate = (total == 0) ? 0.0 : (double) completed / total;

        // 오늘 / 이번 주 일정 수
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        LocalDate weekEnd = today.with(DayOfWeek.SUNDAY);

        long todayCount = all.stream()
                .filter(s -> s.getTargetTime() != null
                        && s.getTargetTime().toLocalDate().equals(today))
                .count();

        long thisWeekCount = all.stream()
                .filter(s -> s.getTargetTime() != null
                        && !s.getTargetTime().toLocalDate().isBefore(weekStart)
                        && !s.getTargetTime().toLocalDate().isAfter(weekEnd))
                .count();

        // 요일별 분포 (월~일 모두 0으로 초기화 후 누적)
        Map<String, Long> byDay = new HashMap<>();
        for (DayOfWeek dow : DayOfWeek.values()) {
            byDay.put(dow.name(), 0L);
        }
        for (Schedule s : all) {
            if (s.getTargetTime() != null) {
                String key = s.getTargetTime().getDayOfWeek().name();
                byDay.merge(key, 1L, Long::sum);
            }
        }

        // 시간대별 분포 (0~23시 모두 0으로 초기화 후 누적)
        Map<Integer, Long> byHour = new HashMap<>();
        for (int h = 0; h < 24; h++) byHour.put(h, 0L);
        for (Schedule s : all) {
            if (s.getTargetTime() != null) {
                byHour.merge(s.getTargetTime().getHour(), 1L, Long::sum);
            }
        }

        return DashboardStatsDto.builder()
                .totalSchedules(total)
                .completedSchedules(completed)
                .pendingSchedules(pending)
                .todayCount(todayCount)
                .thisWeekCount(thisWeekCount)
                .completionRate(rate)
                .byDayOfWeek(byDay)
                .byHour(byHour)
                .build();
    }
}
