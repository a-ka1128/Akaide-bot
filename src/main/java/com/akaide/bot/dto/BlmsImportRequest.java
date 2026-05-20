package com.akaide.bot.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * BLMS 텍스트/HTML import 요청 바디.
 * mode: "text" (Gemini 분석) 또는 "html" (Jsoup 파싱).
 */
@Getter
@Setter
@NoArgsConstructor
public class BlmsImportRequest {
    private String mode;
    private String content;
}
