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

    /**
     * 종일 일정 플래그.
     *
     * 구글 캘린더에서 종일로 등록된 이벤트(date만 있고 dateTime 이 없는 케이스)는
     * true 로 저장한다. 시간 단위 일정과 모델은 같이 쓰되, 다음 두 가지가 달라진다:
     *   1) 알람 발송 안 함(자정 알람이 사용자에게 의미 없음)
     *   2) 프론트가 FullCalendar 에 allDay:true 로 넘겨 UI 상 별도 표시
     *
     * targetTime/startTime/endTime 은 그날 00:00 / 23:59:59 로 채워서
     * 기존 조회·정렬 로직과 호환되게 한다.
     */
    private boolean allDay;

    /**
     * 일정 카테고리. 캘린더에서 색 구분 + 통계 분류에 사용.
     *
     * 사용자가 웹 모달에서 수정 가능. 봇/AI/구글 import 가 만든 일정은 기본값 OTHER 로 저장된다.
     * DB 에는 enum 이름("SCHOOL", "WORK" ...) 그대로 저장 — ORDINAL 기반 저장은 enum 순서 바꿀 때
     * 데이터가 어긋나므로 의도적으로 STRING 으로 둔다.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    @Builder.Default
    private ScheduleCategory category = ScheduleCategory.OTHER;
}