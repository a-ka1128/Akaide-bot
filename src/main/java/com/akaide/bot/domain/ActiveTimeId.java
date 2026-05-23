package com.akaide.bot.domain;

import lombok.*;

import java.io.Serializable;
import java.util.Objects;

/**
 * ActiveTime 복합키. (userId + 요일) 조합으로 사용자별 활동 시간을 분리한다.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class ActiveTimeId implements Serializable {
    private String userId;
    private String dayOfWeek;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ActiveTimeId that)) return false;
        return Objects.equals(userId, that.userId)
                && Objects.equals(dayOfWeek, that.dayOfWeek);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, dayOfWeek);
    }
}
