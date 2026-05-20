package com.akaide.bot.repository;

import com.akaide.bot.domain.GoogleToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface GoogleTokenRepository extends JpaRepository<GoogleToken, String> {
    // 디스코드 유저 ID로 토큰 정보 찾기
    Optional<GoogleToken> findByUserId(String userId);
}