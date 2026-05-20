package com.akaide.bot.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * 빈 시간 분석 응답 DTO (히트맵용).
 *
 * 구조:
 *   slotMinutes = 30  (한 칸이 30분 단위)
 *   activeRange = {MONDAY: [9, 23], TUESDAY: [9, 23], ...}
 *   occupancy   = {MONDAY: [0,0,0,1,1,0,...], ...}   ← 0=비어있음, 1=일정 있음
 *
 * 프론트는 activeRange로 가로 시간축 범위를, occupancy로 칸 색을 그린다.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FreeTimeDto {
    private int slotMinutes;
    /** 요일별 [시작시각(0~24), 종료시각(0~24)] */
    private Map<String, int[]> activeRange;
    /** 요일별 점유 배열 (0=비어있음, 1=일정 있음). 길이 = 48 (24*60/30) */
    private Map<String, int[]> occupancy;
}
