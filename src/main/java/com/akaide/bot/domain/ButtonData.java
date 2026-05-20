package com.akaide.bot.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ButtonData {
    @Id
    private String id; // UUID가 들어갈 자리

    private String task;
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    private LocalDateTime createdAt; // 나중에 오래된 데이터를 삭제(Cleanup)할 때 사용
}