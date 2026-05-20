package com.akaide.bot.repository;

import com.akaide.bot.domain.TargetChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TargetChannelRepository extends JpaRepository<TargetChannel, String> {
    // 특정 채널 ID가 등록되어 있는지 확인하는 용도
    boolean existsByChannelId(String channelId);
}