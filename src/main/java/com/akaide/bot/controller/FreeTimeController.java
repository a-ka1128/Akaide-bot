package com.akaide.bot.controller;

import com.akaide.bot.domain.ActiveTime;
import com.akaide.bot.domain.Schedule;
import com.akaide.bot.dto.FreeTimeDto;
import com.akaide.bot.repository.ActiveTimeRepository;
import com.akaide.bot.repository.ScheduleRepository;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 빈 시간(가용 시간) 히트맵 데이터 API.
 *
 * 이번 주 월요일 ~ 일요일 까지의 본인 일정을 30분 슬롯으로 점유 처리한 결과를 반환.
 */
@RestController
@RequestMapping("/api/free-time")
public class FreeTimeController {

    private static final int SLOT_MINUTES = 30;
    private static final int SLOTS_PER_DAY = 24 * 60 / SLOT_MINUTES; // 48

    private final ScheduleRepository scheduleRepository;
    private final ActiveTimeRepository activeTimeRepository;

    public FreeTimeController(ScheduleRepository scheduleRepository,
                              ActiveTimeRepository activeTimeRepository) {
        this.scheduleRepository = scheduleRepository;
        this.activeTimeRepository = activeTimeRepository;
    }

    /** GET /api/free-time — 이번 주 점유 매트릭스 + 활동 시간대 반환 */
    @GetMapping
    public FreeTimeDto getMatrix(@AuthenticationPrincipal String userId) {
        LocalDate today = LocalDate.now();
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        LocalDate weekEnd = weekStart.plusDays(6);

        LocalDateTime from = weekStart.atStartOfDay();
        LocalDateTime to = weekEnd.atTime(LocalTime.MAX);

        // 활동 시간 (요일별)
        Map<String, int[]> activeRange = new LinkedHashMap<>();
        for (DayOfWeek dow : DayOfWeek.values()) {
            ActiveTime a = activeTimeRepository.findById(dow.name())
                    .orElse(new ActiveTime(dow.name(), 9, 23));
            activeRange.put(dow.name(), new int[]{ a.getStartHour(), a.getEndHour() });
        }

        // 본인 일정 (이번 주)
        List<Schedule> schedules = scheduleRepository
                .findAllByUserIdAndTargetTimeBetween(userId, from, to);

        // 요일별 occupancy 배열 (길이 48)
        Map<String, int[]> occupancy = new LinkedHashMap<>();
        for (DayOfWeek dow : DayOfWeek.values()) {
            occupancy.put(dow.name(), new int[SLOTS_PER_DAY]);
        }

        for (Schedule s : schedules) {
            if (s.getTargetTime() == null) continue;
            if (s.getCompletedAt() != null) continue; // 완료된 건 점유 안 한 걸로 (선택)

            LocalDateTime start = s.getTargetTime();
            LocalDateTime end = (s.getEndTime() != null) ? s.getEndTime() : start.plusHours(1);

            // 만약 일정이 자정을 넘긴다면, 시작 요일에만 표시 (단순화)
            String dayKey = start.getDayOfWeek().name();
            int[] arr = occupancy.get(dayKey);
            if (arr == null) continue;

            int startSlot = start.getHour() * 2 + (start.getMinute() / SLOT_MINUTES);
            int endSlot = end.getHour() * 2 + ((end.getMinute() + SLOT_MINUTES - 1) / SLOT_MINUTES);
            if (endSlot > SLOTS_PER_DAY) endSlot = SLOTS_PER_DAY;
            if (startSlot < 0) startSlot = 0;
            for (int i = startSlot; i < endSlot; i++) arr[i] = 1;
        }

        return FreeTimeDto.builder()
                .slotMinutes(SLOT_MINUTES)
                .activeRange(activeRange)
                .occupancy(occupancy)
                .build();
    }
}
