package com.akaide.bot.controller;

import com.akaide.bot.domain.TokenUsage;
import com.akaide.bot.dto.TokenUsageDto;
import com.akaide.bot.repository.TokenUsageRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gemini API 토큰 사용량 조회용 REST API.
 */
@RestController
@RequestMapping("/api/token-usage")
public class TokenController {

    private final TokenUsageRepository tokenUsageRepository;

    public TokenController(TokenUsageRepository tokenUsageRepository) {
        this.tokenUsageRepository = tokenUsageRepository;
    }

    /** GET /api/token-usage — 누적 토큰 사용량 */
    @GetMapping
    public TokenUsageDto getUsage() {
        TokenUsage usage = tokenUsageRepository.findById("total_usage")
                .orElse(TokenUsage.builder()
                        .id("total_usage")
                        .totalPromptTokens(0L)
                        .totalCandidateTokens(0L)
                        .totalTokens(0L)
                        .build());
        return TokenUsageDto.from(usage);
    }
}
