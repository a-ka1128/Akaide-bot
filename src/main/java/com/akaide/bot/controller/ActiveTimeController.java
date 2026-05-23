package com.akaide.bot.controller;

import com.akaide.bot.domain.ActiveTime;
import com.akaide.bot.dto.ActiveTimeDto;
import com.akaide.bot.repository.ActiveTimeRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 요일별 활동 시간 (=가용 시간 분석 기준) 조회/수정 API.
 *
 * ActiveTime 은 (userId + 요일) 복합키라 사용자마다 독립적인 활동 시간을 가진다.
 */
@RestController
@RequestMapping("/api/active-time")
public class ActiveTimeController {

    private final ActiveTimeRepository activeTimeRepository;

    public ActiveTimeController(ActiveTimeRepository activeTimeRepository) {
        this.activeTimeRepository = activeTimeRepository;
    }

    /** GET /api/active-time — 본인의 월~일 7개를 모두 반환 (없으면 기본값 9~23). */
    @GetMapping
    public List<ActiveTimeDto> getAll(@AuthenticationPrincipal String userId) {
        // 요일 순서 보장 (DayOfWeek.MONDAY ~ SUNDAY)
        Map<String, ActiveTimeDto> result = new LinkedHashMap<>();
        for (DayOfWeek dow : DayOfWeek.values()) {
            ActiveTime existing = activeTimeRepository.findByUserIdAndDayOfWeek(userId, dow.name())
                    .orElse(new ActiveTime(userId, dow.name(), 9, 23));
            result.put(dow.name(), ActiveTimeDto.from(existing));
        }
        return result.values().stream().toList();
    }

    /** PUT /api/active-time/{day} — 본인의 한 요일 갱신. day는 MONDAY 등 영문 대문자. */
    @PutMapping("/{day}")
    public ResponseEntity<ActiveTimeDto> update(@AuthenticationPrincipal String userId,
                                                @PathVariable String day,
                                                @RequestBody ActiveTimeDto body) {
        // 요일 유효성
        String upper = day.toUpperCase();
        try {
            DayOfWeek.valueOf(upper);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }

        int start = body.getStartHour();
        int end = body.getEndHour();
        if (start < 0 || start > 23 || end < 0 || end > 24 || start >= end) {
            return ResponseEntity.badRequest().build();
        }

        ActiveTime saved = activeTimeRepository.save(new ActiveTime(userId, upper, start, end));
        return ResponseEntity.ok(ActiveTimeDto.from(saved));
    }
}
