package com.akaide.bot.repository;

import com.akaide.bot.domain.OAuthState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Repository
public interface OAuthStateRepository extends JpaRepository<OAuthState, String> {

    @Modifying
    @Transactional
    void deleteByCreatedAtBefore(LocalDateTime threshold);
}
