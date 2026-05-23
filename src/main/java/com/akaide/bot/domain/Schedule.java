package com.akaide.bot.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class Schedule {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String task;

    // 단일 일정 시점
    private LocalDateTime targetTime;

    // 기간제 일정 (시작 ~ 종료)
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    private LocalDateTime createdAt;

    private boolean isRepeat;

    @Column(length = 100)
    private String repeatRule;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }

    private String userId;       // 일정을 등록한 사용자 ID (태그용)
    private boolean alert24h;    // 24시간 전 알림 설정
    private boolean alert1h;     // 1시간 전 알림 설정
    private boolean notified24h; // 알림 발송 여부
    private boolean notified1h;  // 알림 발송 여부

    private LocalDateTime completedAt; // ✅ 사용자가 '완료' 처리한 시각 (null이면 미완료)

    /**
     * 연동된 Google Calendar 이벤트 ID.
     *
     * - 앱 → 구글로 push(addEvent)한 일정: 구글이 돌려준 이벤트 ID 저장
     * - 구글 → 앱으로 pull(import)한 일정: 가져온 구글 이벤트 ID 저장
     *
     * 이 값으로 양방향 동기화 시 같은 일정이 중복 생성되는 것을 막는다.
     * 순수 로컬 일정(구글 미연동/미동기화)은 null.
     */
    @Column(length = 256)
    private String googleEventId;
}