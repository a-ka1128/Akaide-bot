package com.akaide.bot.controller;

import com.akaide.bot.domain.BlmsItem;
import com.akaide.bot.domain.Schedule;
import com.akaide.bot.dto.BlmsImportRequest;
import com.akaide.bot.dto.BlmsItemDto;
import com.akaide.bot.dto.ScheduleDto;
import com.akaide.bot.service.BlmsService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 백석대 BLMS 학사 정보 import / 조회 / 일정 연동 API.
 *
 * POST /api/blms/import        — text/html 자동 분기
 * GET  /api/blms/items         — 본인 항목 전체
 * DELETE /api/blms/items/{id}  — 삭제
 * POST /api/blms/items/{id}/to-schedule — 해당 항목을 Schedule 로 등록
 */
@RestController
@RequestMapping("/api/blms")
public class BlmsController {

    private final BlmsService blmsService;

    public BlmsController(BlmsService blmsService) {
        this.blmsService = blmsService;
    }

    /** POST /api/blms/import — mode 가 "html" 이면 Jsoup, "text" 이면 Gemini. */
    @PostMapping("/import")
    public ResponseEntity<List<BlmsItemDto>> importBlms(
            @AuthenticationPrincipal String userId,
            @RequestBody BlmsImportRequest req) {

        if (req.getContent() == null || req.getContent().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        List<BlmsItem> saved = "html".equalsIgnoreCase(req.getMode())
                ? blmsService.importFromHtml(req.getContent(), userId)
                : blmsService.importFromText(req.getContent(), userId);

        return ResponseEntity.ok(saved.stream().map(BlmsItemDto::from).toList());
    }

    /** GET /api/blms/items — 본인 전체 항목 (마감일 오름차순) */
    @GetMapping("/items")
    public List<BlmsItemDto> list(@AuthenticationPrincipal String userId) {
        return blmsService.listByUser(userId).stream().map(BlmsItemDto::from).toList();
    }

    /** DELETE /api/blms/items/{id} */
    @DeleteMapping("/items/{id}")
    public ResponseEntity<Void> remove(@AuthenticationPrincipal String userId,
                                       @PathVariable Long id) {
        return blmsService.deleteItem(id, userId)
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** POST /api/blms/items/{id}/to-schedule — 일정으로 등록 */
    @PostMapping("/items/{id}/to-schedule")
    public ResponseEntity<ScheduleDto> toSchedule(@AuthenticationPrincipal String userId,
                                                  @PathVariable Long id) {
        try {
            Schedule s = blmsService.convertToSchedule(id, userId);
            return ResponseEntity.ok(ScheduleDto.from(s));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
