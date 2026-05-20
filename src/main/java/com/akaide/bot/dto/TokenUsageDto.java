package com.akaide.bot.dto;

import com.akaide.bot.domain.TokenUsage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Gemini API 토큰 사용량 응답 DTO.
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsageDto {
    private long totalPromptTokens;
    private long totalCandidateTokens;
    private long totalTokens;

    public static TokenUsageDto from(TokenUsage usage) {
        return TokenUsageDto.builder()
                .totalPromptTokens(usage.getTotalPromptTokens())
                .totalCandidateTokens(usage.getTotalCandidateTokens())
                .totalTokens(usage.getTotalTokens())
                .build();
    }
}
