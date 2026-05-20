package com.akaide.bot.repository;

import com.akaide.bot.domain.ButtonData;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface ButtonDataRepository extends JpaRepository<ButtonData, String> {
    // 🧹 특정 시간(createdAt) 이전의 데이터를 모두 삭제하는 쿼리 메서드 추가!
    void deleteByCreatedAtBefore(LocalDateTime threshold);
}