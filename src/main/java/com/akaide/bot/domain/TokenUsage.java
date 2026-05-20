package com.akaide.bot.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.*;

@Entity
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class TokenUsage {
    @Id
    private String id; // "total_usage"로 고정
    private long totalPromptTokens;
    private long totalCandidateTokens;
    private long totalTokens;
}