package com.akaide.bot.dto;

import com.akaide.bot.domain.BlmsItem;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BlmsItemDto {
    private Long id;
    private String kind;
    private String title;
    private String courseName;
    private LocalDateTime startAt;
    private LocalDateTime endAt;
    private String status;
    private boolean done;
    private String sourceCode;
    private String sourceUrl;
    private Long linkedScheduleId;

    public static BlmsItemDto from(BlmsItem i) {
        return BlmsItemDto.builder()
                .id(i.getId())
                .kind(i.getKind())
                .title(i.getTitle())
                .courseName(i.getCourseName())
                .startAt(i.getStartAt())
                .endAt(i.getEndAt())
                .status(i.getStatus())
                .done(i.isDone())
                .sourceCode(i.getSourceCode())
                .sourceUrl(i.getSourceUrl())
                .linkedScheduleId(i.getLinkedScheduleId())
                .build();
    }
}
