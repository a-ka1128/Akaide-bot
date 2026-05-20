package com.akaide.bot.service;

import com.akaide.bot.domain.Schedule;
import com.akaide.bot.domain.TokenUsage;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.MessageEmbed;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
public class EmbedService {

    private final DateTimeFormatter kornFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd a hh:mm").withLocale(Locale.KOREAN);

    // 같은 날짜면 시작만 풀 표기, 종료는 시간만 보여주는 헬퍼
    private static final DateTimeFormatter TIME_ONLY = DateTimeFormatter.ofPattern("a hh:mm").withLocale(Locale.KOREAN);

    // 1. 가용 시간 리포트 UI
    public MessageEmbed createFreeTimeEmbed(String analysisResult, String avatarUrl) {
        return new EmbedBuilder()
                .setTitle("⏳ Akaide 가용 시간 리포트")
                .setColor(Color.ORANGE)
                .setDescription(analysisResult)
                .setFooter("생산성을 응원합니다! 🚀", avatarUrl)
                .build();
    }

    // 2. 스마트 시간 추천 UI
    public MessageEmbed createSuggestionEmbed(String suggestionText, String task, LocalDateTime start, LocalDateTime end) {
        // 💡 시작/종료 포맷 일관성: 같은 날이면 "yyyy-MM-dd a hh:mm ~ a hh:mm"
        //                          다른 날이면 양쪽 모두 풀 표기.
        String startStr = start.format(kornFormatter);
        String endStr = (end.toLocalDate().equals(start.toLocalDate()))
                ? end.format(TIME_ONLY)
                : end.format(kornFormatter);

        return new EmbedBuilder()
                .setTitle("💡 스마트 시간 추천")
                .setColor(Color.CYAN)
                .setDescription(suggestionText)
                .addField("추천 일정", task, true)
                .addField("추천 시간", startStr + " ~ " + endStr, false)
                .build();
    }

    // 3. 전체 일정 목록 UI
    public MessageEmbed createScheduleListEmbed(List<Schedule> list) {
        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("📅 현재 일정 목록")
                .setColor(Color.decode("#228B22"));
        for (Schedule s : list) {
            String timeStr;
            if (s.isRepeat()) {
                timeStr = "🔄 [반복] " + s.getRepeatRule();
            } else if (s.getTargetTime() != null) {
                timeStr = s.getTargetTime().format(kornFormatter);
            } else {
                timeStr = "(시간 미정)";
            }
            eb.addField(s.getTask(), "🕒 " + timeStr, false);
        }
        return eb.build();
    }

    // 6. 충돌 알림 임베드
    public MessageEmbed createConflictEmbed(String task, LocalDateTime start, LocalDateTime end, String conflictDescription) {
        return new EmbedBuilder()
                .setTitle("⚠️ 일정 충돌 감지")
                .setColor(Color.RED)
                .setDescription("이 시간에 이미 다른 일정이 있어요. 그래도 등록할까요?")
                .addField("새 일정", task, false)
                .addField("새 시간",
                        start.format(kornFormatter) +
                                ((end != null) ? " ~ " + end.format(TIME_ONLY) : ""), false)
                .addField("기존 일정", conflictDescription, false)
                .build();
    }

    // 5. 일자별 일정 (오늘/내일)
    public MessageEmbed createDayScheduleEmbed(String dayLabel,
                                               java.time.LocalDate date,
                                               List<Schedule> dbSchedules,
                                               List<com.google.api.services.calendar.model.Event> googleEvents) {
        EmbedBuilder eb = new EmbedBuilder()
                .setTitle("📆 " + dayLabel + " 일정 (" + date + ")")
                .setColor(Color.decode("#4A90E2"));

        DateTimeFormatter timeFmt = DateTimeFormatter.ofPattern("a hh:mm").withLocale(Locale.KOREAN);

        // (1) DB 일정 정렬 (시간 없는 건 맨 뒤)
        dbSchedules.sort((a, b) -> {
            LocalDateTime ta = a.getTargetTime();
            LocalDateTime tb = b.getTargetTime();
            if (ta == null && tb == null) return 0;
            if (ta == null) return 1;
            if (tb == null) return -1;
            return ta.compareTo(tb);
        });

        if (dbSchedules.isEmpty() && (googleEvents == null || googleEvents.isEmpty())) {
            eb.setDescription("📭 등록된 일정이 없어요. 여유로운 하루 보내세요!");
            return eb.build();
        }

        for (Schedule s : dbSchedules) {
            String done = (s.getCompletedAt() != null) ? "✅ " : "🕒 ";
            String timeStr = (s.getTargetTime() != null) ? s.getTargetTime().format(timeFmt) : "(시간 미정)";
            eb.addField(done + s.getTask(), timeStr + "  ·  봇 등록", false);
        }

        if (googleEvents != null) {
            for (com.google.api.services.calendar.model.Event e : googleEvents) {
                String summary = (e.getSummary() != null) ? e.getSummary() : "(제목 없음)";
                String when;
                try {
                    long ms = e.getStart().getDateTime() != null
                            ? e.getStart().getDateTime().getValue()
                            : e.getStart().getDate().getValue();
                    LocalDateTime ldt = LocalDateTime.ofInstant(
                            java.time.Instant.ofEpochMilli(ms),
                            java.time.ZoneId.systemDefault());
                    when = ldt.format(timeFmt);
                } catch (Exception ex) {
                    when = "(시간 정보 없음)";
                }
                eb.addField("🗓️ " + summary, when + "  ·  Google Calendar", false);
            }
        }
        return eb.build();
    }

    // 4. 토큰 사용량 UI
    public MessageEmbed createTokenUsageEmbed(TokenUsage usage) {
        // 💡 포맷 오타 수정: "%, d" → "%,d" (천 단위 구분자)
        return new EmbedBuilder()
                .setTitle("💎 토큰 보고서")
                .setColor(Color.cyan)
                .addField("📊 총합", String.format("%,d", usage.getTotalTokens()) + " tokens", false)
                .build();
    }
}