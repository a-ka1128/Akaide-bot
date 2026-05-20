package com.akaide.bot.controller;

import com.akaide.bot.domain.Schedule;
import com.akaide.bot.dto.CreateScheduleRequest;
import com.akaide.bot.dto.ScheduleDto;
import com.akaide.bot.dto.SmartCreateRequest;
import com.akaide.bot.dto.SmartResultDto;
import com.akaide.bot.dto.UpdateScheduleRequest;
import com.akaide.bot.repository.ScheduleRepository;
import com.akaide.bot.service.ScheduleService;
import com.akaide.bot.service.SmartResult;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 일정 조회/수정용 REST API.
 *
 * Phase 2부터는 SecurityContext의 principal(=discord userId)을
 * @AuthenticationPrincipal 로 주입받아, 본인 데이터만 반환한다.
 */
@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    private final ScheduleRepository scheduleRepository;
    private final ScheduleService scheduleService;

    public ScheduleController(ScheduleRepository scheduleRepository, ScheduleService scheduleService) {
        this.scheduleRepository = scheduleRepository;
        this.scheduleService = scheduleService;
    }

    /** GET /api/schedules — 본인의 전체 일정 (시간순 정렬) */
    @GetMapping
    public List<ScheduleDto> getAll(@AuthenticationPrincipal String userId) {
        return scheduleRepository.findAllByUserId(userId).stream()
                .sorted(Comparator.comparing(
                        Schedule::getTargetTime,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .map(ScheduleDto::from)
                .toList();
    }

    /** GET /api/schedules/today — 본인의 오늘 일정 */
    @GetMapping("/today")
    public List<ScheduleDto> getToday(@AuthenticationPrincipal String userId) {
        return scheduleService.getSchedulesForDate(LocalDate.now(), userId).stream()
                .map(ScheduleDto::from)
                .toList();
    }

    /** GET /api/schedules/date/2026-05-12 — 본인의 특정 날짜 일정 */
    @GetMapping("/date/{date}")
    public List<ScheduleDto> getByDate(
            @AuthenticationPrincipal String userId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return scheduleService.getSchedulesForDate(date, userId).stream()
                .map(ScheduleDto::from)
                .toList();
    }

    /** GET /api/schedules/range?start=&end= — 본인 일정의 범위 조회 (캘린더용) */
    @GetMapping("/range")
    public List<ScheduleDto> getByRange(
            @AuthenticationPrincipal String userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime to = end.atTime(LocalTime.MAX);
        return scheduleRepository.findAllByUserIdAndTargetTimeBetween(userId, from, to).stream()
                .map(ScheduleDto::from)
                .toList();
    }

    /** GET /api/schedules/{id} — 단일 조회 (본인 일정만) */
    @GetMapping("/{id}")
    public ResponseEntity<ScheduleDto> getOne(@AuthenticationPrincipal String userId,
                                              @PathVariable Long id) {
        return scheduleRepository.findById(id)
                .filter(s -> userId.equals(s.getUserId()))   // 다른 사람 일정이면 404로 위장
                .map(ScheduleDto::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** POST /api/schedules/{id}/complete — 완료 처리 (본인 일정만) */
    @PostMapping("/{id}/complete")
    public ResponseEntity<String> complete(@AuthenticationPrincipal String userId,
                                           @PathVariable Long id) {
        return scheduleRepository.findById(id)
                .filter(s -> userId.equals(s.getUserId()))
                .map(s -> {
                    scheduleService.markScheduleCompleted(id);
                    return ResponseEntity.ok("completed");
                })
                .orElseGet(() -> ResponseEntity.status(404).body("not_found"));
    }

    /** PATCH /api/schedules/{id} — 일정 부분 수정 (본인 일정만) */
    @PatchMapping("/{id}")
    public ResponseEntity<ScheduleDto> update(@AuthenticationPrincipal String userId,
                                              @PathVariable Long id,
                                              @RequestBody UpdateScheduleRequest req) {
        return scheduleRepository.findById(id)
                .filter(s -> userId.equals(s.getUserId()))
                .map(s -> {
                    if (req.getTask() != null && !req.getTask().isBlank()) {
                        s.setTask(req.getTask());
                    }
                    if (req.getTargetTime() != null) {
                        s.setTargetTime(req.getTargetTime());
                        s.setStartTime(req.getTargetTime());
                        // 시간이 미래로 바뀌면 다시 알림이 가도록 플래그 초기화
                        s.setNotified1h(false);
                        s.setNotified24h(false);
                    }
                    if (req.getEndTime() != null) {
                        s.setEndTime(req.getEndTime());
                    }
                    if (req.getAlert24h() != null) {
                        s.setAlert24h(req.getAlert24h());
                    }
                    if (req.getAlert1h() != null) {
                        s.setAlert1h(req.getAlert1h());
                    }
                    Schedule saved = scheduleRepository.save(s);
                    return ResponseEntity.ok(ScheduleDto.from(saved));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** DELETE /api/schedules/{id} — 삭제 (본인 일정만) */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal String userId,
                                       @PathVariable Long id) {
        return scheduleRepository.findById(id)
                .filter(s -> userId.equals(s.getUserId()))
                .map(s -> {
                    scheduleRepository.deleteById(id);
                    return ResponseEntity.noContent().<Void>build();
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    // =================================================================
    // 일정 생성 API (웹 대시보드용)
    // =================================================================

    /** POST /api/schedules — 폼으로 직접 입력해서 등록 */
    @PostMapping
    public ResponseEntity<SmartResultDto> create(@AuthenticationPrincipal String userId,
                                                 @RequestBody CreateScheduleRequest req) {
        if (req.getTask() == null || req.getTask().isBlank() || req.getTargetTime() == null) {
            return ResponseEntity.badRequest().build();
        }
        SmartResult result = scheduleService.createFromForm(
                req.getTask(),
                req.getTargetTime(),
                req.getEndTime(),
                req.isAlert24h(),
                req.isAlert1h(),
                userId
        );
        return ResponseEntity.ok(SmartResultDto.from(result));
    }

    /** POST /api/schedules/smart — 자연어로 등록 (Gemini AI 분석) */
    @PostMapping("/smart")
    public ResponseEntity<SmartResultDto> createSmart(@AuthenticationPrincipal String userId,
                                                      @RequestBody SmartCreateRequest req) {
        if (req.getMessage() == null || req.getMessage().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            // processSmartMessage 는 @Async라 CompletableFuture를 반환. 웹 응답은 동기여야 하므로 get() 으로 대기.
            // 60초 타임아웃: Gemini API가 느리거나 응답 없을 때 무한 대기 방지.
            SmartResult result = scheduleService.processSmartMessage(req.getMessage(), userId)
                    .get(60, TimeUnit.SECONDS);
            return ResponseEntity.ok(SmartResultDto.from(result));
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(500)
                    .body(SmartResultDto.builder().type("ERROR").build());
        }
    }

    /** POST /api/schedules/confirm/{buttonId} — 충돌/추천 확정 (그래도 등록) */
    @PostMapping("/confirm/{buttonId}")
    public ResponseEntity<String> confirm(@AuthenticationPrincipal String userId,
                                          @PathVariable String buttonId) {
        try {
            String task = scheduleService.confirmRecommendation(buttonId, userId)
                    .get(60, TimeUnit.SECONDS);
            return ResponseEntity.ok(task);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(500).body("error");
        }
    }
}
