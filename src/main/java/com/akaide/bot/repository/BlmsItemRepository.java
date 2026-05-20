package com.akaide.bot.repository;

import com.akaide.bot.domain.BlmsItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BlmsItemRepository extends JpaRepository<BlmsItem, Long> {
    List<BlmsItem> findAllByUserIdOrderByEndAtAsc(String userId);
    Optional<BlmsItem> findByUserIdAndSourceCode(String userId, String sourceCode);
}
