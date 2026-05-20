package com.akaide.bot.dto;

import com.akaide.bot.service.SmartResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 자연어 일정 등록(POST /api/schedules/smart) 결과를 프론트로 내려보낼 때 쓰는 DTO.
 *
 * type 별 의미:
 *   SUCCESS    : 즉시 등록 성공 (충돌 없음, 추천 아님)
 *   CONFLICT   : 기존 일정과 겹침. buttonId로 확정 요청 필요.
 *   SUGGESTION : Gemini가 시간 추천. buttonId로 확정 요청 필요.
 *   IGNORE     : 일정으로 인식되지 않음
 *   ERROR      : 분석 실패
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SmartResultDto {
    private String type;          // SUCCESS / CONFLICT / SUGGESTION / IGNORE / ERROR
    private String task;
    private String text;          // 추천 사유 (SUGGESTION일 때)
    private LocalDateTime start;
    private LocalDateTime end;
    private String buttonId;      // CONFLICT/SUGGESTION 확정용 토큰
    private String conflictDescription;

    public static SmartResultDto from(SmartResult r) {
        return SmartResultDto.builder()
                .type(r.getType().name())
                .task(r.getTask())
                .text(r.getText())
                .start(r.getStart())
                .end(r.getEnd())
                .buttonId(r.getButtonId())
                .conflictDescription(r.getConflictDescription())
                .build();
    }
}
