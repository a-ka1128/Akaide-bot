package com.akaide.bot.domain;

/**
 * 일정 카테고리.
 *
 * 자유 입력이 아닌 사전 정의된 enum 으로 두어 오타·중복 카테고리(예: "학교" vs "학교 ")가
 * 생기지 않도록 한다. 색은 프론트가 카테고리 코드로 매핑하므로 백엔드에는 들고 있지 않는다.
 *
 * 새 카테고리를 추가할 일이 생기면 여기와 프론트의 카테고리 메타(category.js)를 같이 수정.
 */
public enum ScheduleCategory {
    SCHOOL,   // 학교
    WORK,     // 작업
    WOW,      // 월드오브워크래프트
    HOBBY,    // 취미+약속
    OTHER;    // 기타 (기본값)

    /** null-safe 기본값 fallback */
    public static ScheduleCategory orDefault(ScheduleCategory c) {
        return c != null ? c : OTHER;
    }
}
