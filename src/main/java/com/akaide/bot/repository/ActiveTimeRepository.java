package com.akaide.bot.repository;

import com.akaide.bot.domain.ActiveTime;
import com.akaide.bot.domain.ActiveTimeId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ActiveTimeRepository extends JpaRepository<ActiveTime, ActiveTimeId> {

    /** 특정 사용자의 특정 요일 활동 시간 조회 */
    Optional<ActiveTime> findByUserIdAndDayOfWeek(String userId, String dayOfWeek);
}
