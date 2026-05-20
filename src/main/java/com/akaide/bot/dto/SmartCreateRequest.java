package com.akaide.bot.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * POST /api/schedules/smart 요청 바디.
 * 사용자가 자연어로 입력한 메시지를 그대로 담음.
 */
@Getter
@Setter
@NoArgsConstructor
public class SmartCreateRequest {
    private String message;
}
