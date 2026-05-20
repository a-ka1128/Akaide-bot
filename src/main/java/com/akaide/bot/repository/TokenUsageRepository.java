package com.akaide.bot.repository;

import com.akaide.bot.domain.TokenUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TokenUsageRepository extends JpaRepository<TokenUsage, String> {
}