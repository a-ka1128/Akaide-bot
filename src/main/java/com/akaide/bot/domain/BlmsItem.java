package com.akaide.bot.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * 백석대 사이버캠퍼스(BLMS)에서 수집한 학사 항목.
 *
 * 과제(ASSIGNMENT), 토론(FORUM), 공지(NOTICE), 주차별 학습(LESSON) 등을 하나의 테이블에
 * kind 컬럼으로 구분해 저장한다.
 *
 * sourceCode 는 BLMS 의 원본 식별자 (예: "ASMNT_260511T0238059f87ba1") 로,
 * 중복 import 시 같은 항목을 다시 만들지 않도록 (userId, sourceCode) UNIQUE 로 보장한다.
 */
@Entity
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@Table(
    name = "blms_item",
    uniqueConstraints = @UniqueConstraint(columnNames = {"userId", "sourceCode"})
)
public class BlmsItem {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 소유자 Discord ID */
    private String userId;

    /** ASSIGNMENT / FORUM / NOTICE / LESSON / UNKNOWN */
    @Column(length = 20)
    private String kind;

    /** 항목 제목 (예: "기말프로젝트제출14주차") */
    @Column(length = 300)
    private String title;

    /** 강의명 (있으면) */
    @Column(length = 200)
    private String courseName;

    /** 시작일시 (제출/토론/주차 기간 시작) */
    private LocalDateTime startAt;

    /** 종료/마감 일시 */
    private LocalDateTime endAt;

    /** 현재 상태 텍스트 (예: "과제를 제출하지 않았습니다", "대기", "진행중") */
    @Column(length = 200)
    private String status;

    /** 완료/제출 여부 — 사용자가 직접 마킹하거나 BLMS 텍스트에서 추론 */
    private boolean done;

    /** BLMS 원본 식별자 (중복 방지 키) */
    @Column(length = 100)
    private String sourceCode;

    /** BLMS 원본 링크 (있다면) */
    @Column(length = 500)
    private String sourceUrl;

    /** Schedule 로 연동된 경우 그 Schedule.id (없으면 null) */
    private Long linkedScheduleId;

    /** 메모/원본 텍스트 발췌 (요약 디버깅용) */
    @Lob
    private String memo;

    private LocalDateTime importedAt;

    @PrePersist
    public void prePersist() {
        if (importedAt == null) importedAt = LocalDateTime.now();
    }
}
