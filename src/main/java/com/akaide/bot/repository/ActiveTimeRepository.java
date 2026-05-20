package com.akaide.bot.repository;

import com.akaide.bot.domain.ActiveTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ActiveTimeRepository extends JpaRepository<ActiveTime, String> {
}