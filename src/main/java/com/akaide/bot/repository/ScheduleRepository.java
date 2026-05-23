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
    List<Schedule> findAllByTargetTimeBefore(LocalDateTime now);

    // 웹 대시보드용: 본인 일정만 조회
    List<Schedule> findAllByUserId(String userId);
    List<Schedule> findAllByUserIdAndTargetTimeBetween(String userId, LocalDateTime start, LocalDateTime end);

    // 본인 일정 중 task 키워드가 포함된 것 (최신순은 서비스에서 처리)
    List<Schedule> findAllByUserIdAndTaskContaining(String userId, String keyword);

    // 본인 일정만 키워드로 삭제
    @Modifying
    @Transactional
    void deleteByUserIdAndTaskContaining(String userId, String keyword);

    // 양방향 동기화: 특정 사용자가 이미 가지고 있는 구글 이벤트 ID 집합 조회 (중복 import 방지)
    List<Schedule> findAllByUserIdAndGoogleEventIdIsNotNull(String userId);

    boolean existsByUserIdAndGoogleEventId(String userId, String googleEventId);

    /**
     * 알림 후보 일정만 조회 (1회성 일정 + 알림 시점이 [now-1day, now] 범위 안)
     * → 매분 findAll() 대신 사용해서 일정 수가 많아져도 부하를 최소화한다.
     */
    List<Schedule> findAllByIsRepeatFalseAndTargetTimeBetween(LocalDateTime start, LocalDateTime end);

    /**
     * 반복 일정만 조회 (반복 여부 + 규칙 매칭은 서비스 레이어에서 처리)
     */
    List<Schedule> findAllByIsRepeatTrue();

}