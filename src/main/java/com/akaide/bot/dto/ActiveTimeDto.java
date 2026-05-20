package com.akaide.bot.dto;

import com.akaide.bot.domain.ActiveTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 요일별 활동 시간 응답/요청 DTO.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActiveTimeDto {
    private String dayOfWeek;
    private int startHour;
    private int endHour;

    public static ActiveTimeDto from(ActiveTime a) {
        return ActiveTimeDto.builder()
                .dayOfWeek(a.getDayOfWeek())
                .startHour(a.getStartHour())
                .endHour(a.getEndHour())
                .build();
    }
}
