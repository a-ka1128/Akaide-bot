package com.akaide.bot.repository;

import com.akaide.bot.domain.Schedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ScheduleRepository extends JpaRepository<Schedule, Long> {
    List<Schedule> findAllByTargetTime(LocalDateTime targetTime);
    List<Schedule> findAllByTargetTimeBefore(LocalDateTime now);

    // 분석 기능을 위해 필요한 메서드
    List<Schedule> findAllByTargetTimeBetween(LocalDateTime start, LocalDateTime end);

    // 웹 대시보드용: 본인 일정만 조회
    List<Schedule> findAllByUserId(String userId);
    List<Schedule> findAllByUserIdAndTargetTimeBetween(String userId, LocalDateTime start, LocalDateTime end);

    /**
     * 알림 후보 일정만 조회 (1회성 일정 + 알림 시점이 [now-1day, now] 범위 안)
     * → 매분 findAll() 대신 사용해서 일정 수가 많아져도 부하를 최소화한다.
     */
    List<Schedule> findAllByIsRepeatFalseAndTargetTimeBetween(LocalDateTime start, LocalDateTime end);

    /**
     * 반복 일정만 조회 (반복 여부 + 규칙 매칭은 서비스 레이어에서 처리)
     */
    List<Schedule> findAllByIsRepeatTrue();

    @Modifying
    @Transactional
    void deleteByTaskContaining(String task);

    boolean existsByTask(String task);
}