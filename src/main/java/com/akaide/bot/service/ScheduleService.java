package com.akaide.bot.service;

import com.akaide.bot.domain.ActiveTime;
import com.akaide.bot.domain.ButtonData;
import com.akaide.bot.domain.Schedule;
import com.akaide.bot.domain.ScheduleCategory;
import com.akaide.bot.domain.TargetChannel;
import com.akaide.bot.domain.GoogleToken;
import com.akaide.bot.repository.ActiveTimeRepository;
import com.akaide.bot.repository.ButtonDataRepository;
import com.akaide.bot.repository.GoogleTokenRepository;
import com.akaide.bot.repository.OAuthStateRepository;
import com.akaide.bot.repository.ScheduleRepository;
import com.akaide.bot.repository.TargetChannelRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.services.calendar.model.Event;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class ScheduleService {

    private final ScheduleRepository scheduleRepository;
    private final TargetChannelRepository targetChannelRepository;
    private final ActiveTimeRepository activeTimeRepository;
    private final ButtonDataRepository buttonDataRepository;
    private final OAuthStateRepository oAuthStateRepository;
    private final GoogleTokenRepository googleTokenRepository;
    private final GoogleCalendarService googleCalendarService;
    private final GeminiService geminiService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JDA jda;

    public ScheduleService(
            ScheduleRepository scheduleRepository,
            TargetChannelRepository targetChannelRepository,
            ActiveTimeRepository activeTimeRepository,
            ButtonDataRepository buttonDataRepository,
            OAuthStateRepository oAuthStateRepository,
            GoogleTokenRepository googleTokenRepository,
            GoogleCalendarService googleCalendarService,
            GeminiService geminiService,
            @Lazy JDA jda) {
        this.scheduleRepository = scheduleRepository;
        this.targetChannelRepository = targetChannelRepository;
        this.activeTimeRepository = activeTimeRepository;
        this.buttonDataRepository = buttonDataRepository;
        this.oAuthStateRepository = oAuthStateRepository;
        this.googleTokenRepository = googleTokenRepository;
        this.googleCalendarService = googleCalendarService;
        this.geminiService = geminiService;
        this.jda = jda;
    }

    // =================================================================
    // 🧠 1. 스마트 비서 핵심 로직 (비동기 AI 연동)
    // =================================================================

    @Async // 👈 비동기 스레드에서 실행
    @Transactional
    public CompletableFuture<SmartResult> processSmartMessage(String userQuery, String authorId) {
        try {
            String freeTimeInfo = analyzeFreeTime(LocalDateTime.now(), authorId);
            String rawJson = geminiService.analyzeMessage(userQuery + "\n\n[참고 가용 시간]\n" + freeTimeInfo);
            JsonNode node = objectMapper.readTree(rawJson);

            if (node.has("task") && (node.get("task").asText().equals("unknown") || node.get("task").asText().equals("error"))) {
                return CompletableFuture.completedFuture(SmartResult.ignore());
            }

            if (node.path("is_suggestion").asBoolean()) {
                String task = node.get("task").asText();
                LocalDateTime start = LocalDateTime.parse(node.get("start").asText());
                LocalDateTime end = LocalDateTime.parse(node.get("end").asText());

                String buttonId = UUID.randomUUID().toString();
                buttonDataRepository.save(ButtonData.builder()
                        .id(buttonId).task(task).startTime(start).endTime(end).createdAt(LocalDateTime.now()).build());

                return CompletableFuture.completedFuture(
                        SmartResult.suggestion(node.path("suggestion_text").asText(), task, start, end, buttonId)
                );
            }

            // ⚠️ 즉시 등록 전 충돌 체크
            LocalDateTime newStart = node.hasNonNull("start")
                    ? LocalDateTime.parse(node.get("start").asText())
                    : (node.hasNonNull("time") ? LocalDateTime.parse(node.get("time").asText()) : null);
            LocalDateTime newEnd = node.hasNonNull("end") ? LocalDateTime.parse(node.get("end").asText()) : null;

            if (newStart != null) {
                List<Schedule> conflicts = findConflicts(newStart, newEnd, authorId);
                if (!conflicts.isEmpty()) {
                    String task = node.get("task").asText();
                    String buttonId = UUID.randomUUID().toString();
                    buttonDataRepository.save(ButtonData.builder()
                            .id(buttonId).task(task).startTime(newStart)
                            .endTime(newEnd != null ? newEnd : newStart.plusHours(1))
                            .createdAt(LocalDateTime.now()).build());

                    StringBuilder desc = new StringBuilder();
                    for (Schedule c : conflicts) {
                        desc.append("• ").append(c.getTask())
                                .append(" (").append(c.getTargetTime()).append(")\n");
                    }
                    return CompletableFuture.completedFuture(
                            SmartResult.conflict(task, newStart, newEnd, buttonId, desc.toString())
                    );
                }
            }

            Schedule schedule = saveFromNode(node, authorId);
            return CompletableFuture.completedFuture(SmartResult.success(schedule.getTask()));

        } catch (Exception e) {
            log.error("메시지 분석 중 오류 발생", e);
            return CompletableFuture.completedFuture(SmartResult.error());
        }
    }

    @Async // 👈 구글 캘린더 통신 비동기 처리
    @Transactional
    public CompletableFuture<String> confirmRecommendation(String buttonId, String userId) {
        ButtonData data = buttonDataRepository.findById(buttonId)
                .orElseThrow(() -> new IllegalArgumentException("만료된 데이터입니다."));

        Schedule s = Schedule.builder()
                .task(data.getTask()).userId(userId)
                .startTime(data.getStartTime()).endTime(data.getEndTime())
                .targetTime(data.getStartTime()).build();

        scheduleRepository.save(s);
        String googleEventId = googleCalendarService.addEvent(userId, data.getTask(), data.getStartTime(), data.getEndTime());
        if (googleEventId != null) {
            s.setGoogleEventId(googleEventId);
            scheduleRepository.save(s);
        }
        buttonDataRepository.delete(data);

        return CompletableFuture.completedFuture(data.getTask());
    }

    private Schedule saveFromNode(JsonNode node, String authorId) {
        Schedule.ScheduleBuilder builder = Schedule.builder()
                .task(node.get("task").asText()).userId(authorId)
                .alert24h(node.path("alert24h").asBoolean(false))
                .alert1h(node.path("alert1h").asBoolean(false));

        if (node.hasNonNull("start") && node.hasNonNull("end")) {
            builder.startTime(LocalDateTime.parse(node.get("start").asText()))
                    .endTime(LocalDateTime.parse(node.get("end").asText()))
                    .targetTime(LocalDateTime.parse(node.get("start").asText()));
        } else {
            builder.targetTime(LocalDateTime.parse(node.get("time").asText()));
        }

        Schedule s = builder.build();
        scheduleRepository.save(s);
        String googleEventId = googleCalendarService.addEvent(
                authorId, s.getTask(),
                s.getStartTime() != null ? s.getStartTime() : s.getTargetTime(),
                s.getEndTime());
        if (googleEventId != null) {
            // push 한 이벤트 ID 를 기록해 두면, 다음 import 때 같은 일정이 중복 생성되지 않는다.
            s.setGoogleEventId(googleEventId);
            scheduleRepository.save(s);
        }
        return s;
    }

    // =================================================================
    // 📅 2. 가용 시간 분석 및 삭제 로직
    // =================================================================

    @Transactional(readOnly = true)
    public String analyzeFreeTime(LocalDateTime targetDate, String userId) {
        String dayName = targetDate.getDayOfWeek().name();
        ActiveTime setting = activeTimeRepository.findByUserIdAndDayOfWeek(userId, dayName)
                .orElse(new ActiveTime(userId, dayName, 9, 23));

        int startH = setting.getStartHour();
        int endH = setting.getEndHour();

        List<Event> googleEvents = googleCalendarService.getEventsForDate(userId, targetDate);
        // 본인 일정만 점유로 계산 (다른 사용자 일정이 섞이지 않도록)
        List<Schedule> dbSchedules = scheduleRepository.findAllByUserIdAndTargetTimeBetween(
                userId, targetDate.with(LocalTime.MIN), targetDate.with(LocalTime.MAX));

        boolean[] occupied = new boolean[48];

        for (Event event : googleEvents) {
            markOccupied(occupied, event.getStart().getDateTime(), event.getEnd().getDateTime());
        }
        for (Schedule s : dbSchedules) {
            if (s.getTargetTime() != null) {
                int slot = s.getTargetTime().getHour() * 2 + (s.getTargetTime().getMinute() >= 30 ? 1 : 0);
                occupied[slot] = true;
            }
        }

        List<String> freeSlots = new ArrayList<>();
        int currentStart = -1;

        for (int i = startH * 2; i < endH * 2; i++) {
            if (!occupied[i]) {
                if (currentStart == -1) currentStart = i;
            } else {
                if (currentStart != -1) {
                    freeSlots.add(formatSlot(currentStart, i));
                    currentStart = -1;
                }
            }
        }
        if (currentStart != -1) freeSlots.add(formatSlot(currentStart, endH * 2));

        StringBuilder sb = new StringBuilder();
        sb.append("📅 **").append(targetDate.toLocalDate()).append(" 가용 시간 분석**\n");
        sb.append("기준: ").append(startH).append("시 ~ ").append(endH).append("시\n\n");

        if (freeSlots.isEmpty()) {
            sb.append("❌ 오늘은 여유 시간이 없네요. 푹 쉬는 것도 중요해요!");
        } else {
            sb.append("✅ **찾은 빈 시간대:**\n");
            for (String slot : freeSlots) sb.append("- ").append(slot).append("\n");

            // 💡 하드코딩된 문구 대신 Gemini가 가용 시간대 기반으로 직접 추천
            String slotsSummary = String.join(", ", freeSlots);
            String aiRec = "";
            try {
                aiRec = geminiService.generateFreeTimeRecommendation(slotsSummary);
            } catch (Exception ex) {
                log.warn("AI 추천 생성 중 예외, 기본 안내로 폴백합니다.", ex);
            }

            sb.append("\n💡 **Aka님을 위한 AI 추천:**\n");
            if (aiRec != null && !aiRec.isBlank()) {
                sb.append("> ").append(aiRec);
            } else {
                // AI 호출 실패 시 부드러운 폴백 (하드코딩된 학습 주제 없이)
                sb.append("> 빈 시간 동안 가볍게 산책하거나 우선순위가 높은 일에 집중해 보세요.");
            }
        }

        return sb.toString();
    }

    private void markOccupied(boolean[] occupied, com.google.api.client.util.DateTime start, com.google.api.client.util.DateTime end) {
        if (start == null || end == null) return;
        long startMillis = start.getValue();
        long endMillis = end.getValue();

        LocalDateTime s = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(startMillis), ZoneId.systemDefault());
        LocalDateTime e = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(endMillis), ZoneId.systemDefault());

        int startIdx = s.getHour() * 2 + (s.getMinute() >= 30 ? 1 : 0);
        int endIdx = e.getHour() * 2 + (e.getMinute() >= 30 ? 1 : 0);

        for (int i = startIdx; i < endIdx && i < 48; i++) occupied[i] = true;
    }

    private String formatSlot(int startIdx, int endIdx) {
        String start = String.format("%02d:%02d", startIdx / 2, (startIdx % 2) * 30);
        String end = String.format("%02d:%02d", endIdx / 2, (endIdx % 2) * 30);
        double duration = (endIdx - startIdx) * 0.5;
        return start + " ~ " + end + " (" + duration + "시간)";
    }

    @Transactional
    public void deleteScheduleByKeyword(String keyword, String userId) {
        scheduleRepository.deleteByUserIdAndTaskContaining(userId, keyword);
    }

    /**
     * 자연어로 일정 수정. 키워드로 가장 최근 일정을 찾고, AI가 변경 내용을 분석해 적용.
     * 실패 시 사용자에게 보여줄 메시지를 CompletableFuture로 반환한다.
     */
    @Async
    @Transactional
    public CompletableFuture<String> editScheduleByNaturalLanguage(String keyword, String instruction, String userId) {
        try {
            // 본인 일정 중에서만 키워드 매칭 (다른 사용자 일정 수정 방지)
            List<Schedule> matched = scheduleRepository.findAllByUserIdAndTaskContaining(userId, keyword).stream()
                    .filter(s -> s.getTask() != null && s.getTask().contains(keyword))
                    .sorted((a, b) -> {
                        LocalDateTime ta = a.getTargetTime();
                        LocalDateTime tb = b.getTargetTime();
                        if (ta == null && tb == null) return 0;
                        if (ta == null) return 1;
                        if (tb == null) return -1;
                        return tb.compareTo(ta); // 최신순
                    })
                    .toList();

            if (matched.isEmpty()) {
                return CompletableFuture.completedFuture("❌ '" + keyword + "' 키워드로 일정을 찾을 수 없어요.");
            }

            Schedule target = matched.get(0);
            String rawJson = geminiService.analyzeEditInstruction(target.getTask(), target.getTargetTime(), instruction);
            JsonNode node = objectMapper.readTree(rawJson);

            if (node.has("error")) {
                return CompletableFuture.completedFuture("❌ 변경 내용을 이해하지 못했어요. 더 구체적으로 말씀해주세요.");
            }

            StringBuilder changes = new StringBuilder();
            if (node.hasNonNull("new_task")) {
                String oldTask = target.getTask();
                target.setTask(node.get("new_task").asText());
                changes.append("📝 제목: ").append(oldTask).append(" → ").append(target.getTask()).append("\n");
            }
            if (node.hasNonNull("new_start")) {
                LocalDateTime newStart = LocalDateTime.parse(node.get("new_start").asText());
                target.setTargetTime(newStart);
                target.setStartTime(newStart);
                target.setNotified1h(false);
                target.setNotified24h(false);
                changes.append("🕒 시간: ").append(newStart).append("\n");
            }
            if (node.hasNonNull("new_end")) {
                target.setEndTime(LocalDateTime.parse(node.get("new_end").asText()));
            }

            scheduleRepository.save(target);

            if (changes.isEmpty()) {
                return CompletableFuture.completedFuture("ℹ️ 변경된 내용이 없어요.");
            }
            return CompletableFuture.completedFuture("✏️ **일정이 수정되었습니다**\n" + changes);
        } catch (Exception e) {
            log.error("일정 수정 중 오류", e);
            return CompletableFuture.completedFuture("❌ 수정 처리 중 오류가 발생했어요.");
        }
    }

    /**
     * 웹에서 폼으로 직접 입력한 일정을 등록한다.
     * 충돌이 있으면 SmartResult.conflict 반환 (확정은 confirmRecommendation 으로),
     * 없으면 즉시 저장하고 SmartResult.success 반환.
     */
    @Transactional
    public SmartResult createFromForm(String task, LocalDateTime targetTime,
                                      LocalDateTime endTime, boolean alert24h, boolean alert1h,
                                      String userId, ScheduleCategory category) {
        // 충돌 체크 (본인 일정 기준)
        List<Schedule> conflicts = findConflicts(targetTime, endTime, userId);
        if (!conflicts.isEmpty()) {
            String buttonId = UUID.randomUUID().toString();
            buttonDataRepository.save(ButtonData.builder()
                    .id(buttonId).task(task).startTime(targetTime)
                    .endTime(endTime != null ? endTime : targetTime.plusHours(1))
                    .createdAt(LocalDateTime.now()).build());

            StringBuilder desc = new StringBuilder();
            for (Schedule c : conflicts) {
                desc.append("• ").append(c.getTask())
                        .append(" (").append(c.getTargetTime()).append(")\n");
            }
            return SmartResult.conflict(task, targetTime, endTime, buttonId, desc.toString());
        }

        // 충돌 없으면 바로 저장
        Schedule s = Schedule.builder()
                .task(task)
                .userId(userId)
                .targetTime(targetTime)
                .startTime(targetTime)
                .endTime(endTime)
                .alert24h(alert24h)
                .alert1h(alert1h)
                .category(ScheduleCategory.orDefault(category))
                .build();
        scheduleRepository.save(s);
        String googleEventId = googleCalendarService.addEvent(userId, task, targetTime, endTime);
        if (googleEventId != null) {
            s.setGoogleEventId(googleEventId);
            scheduleRepository.save(s);
        }
        return SmartResult.success(task);
    }

    /**
     * 일정을 완료 처리 (completedAt 기록).
     */
    @Transactional
    public boolean markScheduleCompleted(Long scheduleId) {
        return scheduleRepository.findById(scheduleId).map(s -> {
            s.setCompletedAt(LocalDateTime.now());
            scheduleRepository.save(s);
            return true;
        }).orElse(false);
    }

    /**
     * 특정 시간대에 본인의 다른 일정이 겹치는지 확인.
     * 겹치는 일정 목록을 반환 (없으면 빈 리스트).
     * userId 로 본인 일정만 대상으로 하여 다른 사용자 일정과의 오탐/정보 노출을 막는다.
     */
    @Transactional(readOnly = true)
    public List<Schedule> findConflicts(LocalDateTime start, LocalDateTime end, String userId) {
        if (start == null) return List.of();
        LocalDateTime windowStart = start.minusHours(1);
        LocalDateTime windowEnd = (end != null) ? end.plusHours(1) : start.plusHours(2);
        return scheduleRepository.findAllByUserIdAndTargetTimeBetween(userId, windowStart, windowEnd).stream()
                .filter(s -> s.getTargetTime() != null)
                .filter(s -> {
                    LocalDateTime sEnd = (s.getEndTime() != null) ? s.getEndTime() : s.getTargetTime().plusHours(1);
                    LocalDateTime newEnd = (end != null) ? end : start.plusHours(1);
                    // 시간 구간이 겹치는지 (a.start < b.end && b.start < a.end)
                    return s.getTargetTime().isBefore(newEnd) && start.isBefore(sEnd);
                })
                .toList();
    }

    /**
     * 특정 사용자의 특정 날짜 일정 목록 (웹 대시보드 / 봇 공용)
     */
    @Transactional(readOnly = true)
    public List<Schedule> getSchedulesForDate(java.time.LocalDate date, String userId) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.atTime(LocalTime.MAX);
        return scheduleRepository.findAllByUserIdAndTargetTimeBetween(userId, start, end);
    }

    /**
     * 특정 날짜의 구글 캘린더 일정 목록 (유저가 연동돼 있으면)
     */
    public List<com.google.api.services.calendar.model.Event> getGoogleEventsForDate(String userId, java.time.LocalDate date) {
        return googleCalendarService.getEventsForDate(userId, date.atStartOfDay());
    }

    // =================================================================
    // ⏰ 3. 백그라운드 일정 알림 발송 (스케줄러)
    // =================================================================

    @Scheduled(cron = "0 * * * * *")
    @Transactional
    public void checkSchedules() {
        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);

        // ✅ 1) 1회성 일정: '지금', '1시간 후', '24시간 후' 알림이 가능한 범위만 조회
        //    targetTime 이 [now, now + 24h] 사이인 것만 가져오면 충분.
        //    단, 종일 일정은 "전날 17:00" 알람을 위해 [now, now + 31h] 까지 봐야 한다.
        //    (오늘 17:00 에 내일 종일 일정의 targetTime=내일 00:00 이 매칭되어야 하므로 약 31h)
        List<Schedule> upcoming = scheduleRepository
                .findAllByIsRepeatFalseAndTargetTimeBetween(now, now.plusHours(31));

        for (Schedule s : upcoming) {
            if (s.getTargetTime() == null) continue;

            // ── 종일 일정 전용 분기 ──
            // 종일 일정은 자정에 정시 알람을 보내봐야 의미 없으니 보내지 않고,
            // 대신 "전날 17:00" 한 번만 알람을 보낸다. notified1h 플래그를 재사용해
            // 같은 일정에 중복 발송되지 않게 막는다(이 일정에는 1h 알람 로직이 안 쓰이므로 충돌 없음).
            if (s.isAllDay()) {
                LocalDateTime alarmAt = s.getTargetTime().minusDays(1).withHour(17).withMinute(0);
                if (!s.isNotified1h() && alarmAt.isEqual(now)) {
                    sendNotification(s, "🗓️ **[내일 종일 일정 알림]**");
                    s.setNotified1h(true);
                }
                continue; // 종일 일정은 아래 일반 분기를 타지 않음
            }

            if (s.getTargetTime().isEqual(now)) {
                // ✅ 완료 버튼을 붙이기 위해 즉시 삭제하지 않음.
                //    cron 이 분 단위라 동일한 targetTime 은 한 번만 매칭되므로 중복 알림 걱정 없음.
                //    완료 처리되지 않은 일정은 cleanupCompletedSchedules 가 다음 날 정리함.
                sendNotificationWithCompleteButton(s, "⏰ **[정시 알림]**");
                continue;
            }

            if (s.isAlert1h() && !s.isNotified1h() && s.getTargetTime().minusHours(1).isEqual(now)) {
                sendNotification(s, "⏳ **[1시간 전 사전 알림]**");
                s.setNotified1h(true);
            }

            if (s.isAlert24h() && !s.isNotified24h() && s.getTargetTime().minusDays(1).isEqual(now)) {
                sendNotification(s, "📅 **[24시간 전 사전 알림]**");
                s.setNotified24h(true);
            }
        }

        // ✅ 2) 반복 일정: targetTime 은 과거여도 되므로 별도 조회
        List<Schedule> repeats = scheduleRepository.findAllByIsRepeatTrue();
        for (Schedule s : repeats) {
            if (s.getTargetTime() == null) continue;
            if (checkRepeatMatch(s, now)) {
                sendNotification(s, "🔄 **[반복 일정 알림]**");
            }
        }
    }

    /**
     * 매일 새벽 4시에 24시간이 지난 임시 버튼 데이터를 삭제합니다. (DB 최적화)
     */
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void cleanupOldButtonData() {
        LocalDateTime threshold = LocalDateTime.now().minusDays(1);
        buttonDataRepository.deleteByCreatedAtBefore(threshold);
        // OAuth state 도 함께 정리 (검증되지 않고 방치된 1회용 토큰 제거)
        LocalDateTime stateThreshold = LocalDateTime.now().minusMinutes(10);
        oAuthStateRepository.deleteByCreatedAtBefore(stateThreshold);
        log.info("🧹 오래된 임시 데이터를 청소했습니다. (버튼 기준: {} 이전, OAuth state 기준: {} 이전)",
                threshold, stateThreshold);
    }

    private boolean checkRepeatMatch(Schedule s, LocalDateTime now) {
        if (s.getRepeatRule() == null) return false;
        LocalTime scheduleTime = s.getTargetTime().toLocalTime();
        if (!scheduleTime.equals(now.toLocalTime())) return false;

        String rule = s.getRepeatRule();
        if (rule.equals("DAILY")) return true;
        if (rule.startsWith("WEEKLY:")) {
            return now.getDayOfWeek().toString().startsWith(rule.split(":")[1]);
        }
        if (rule.equals("WEEKDAY")) {
            int day = now.getDayOfWeek().getValue();
            return day >= 1 && day <= 5;
        }
        return false;
    }

    private void sendNotification(Schedule s, String prefix) {
        List<TargetChannel> channels = targetChannelRepository.findAll();
        for (TargetChannel target : channels) {
            TextChannel tc = jda.getTextChannelById(target.getChannelId());
            if (tc != null) {
                String mention = (s.getUserId() != null) ? "<@" + s.getUserId() + "> " : "";
                tc.sendMessage(mention + prefix + "\n📌 **할 일:** " + s.getTask()).queue();
            }
        }
    }

    /**
     * 정시 알림 전용: 메시지에 ✅ 완료 버튼을 함께 붙여서 발송.
     * 사용자가 버튼을 누르면 해당 일정이 완료 처리되고 DB에서 삭제됨.
     */
    private void sendNotificationWithCompleteButton(Schedule s, String prefix) {
        List<TargetChannel> channels = targetChannelRepository.findAll();
        for (TargetChannel target : channels) {
            TextChannel tc = jda.getTextChannelById(target.getChannelId());
            if (tc != null) {
                String mention = (s.getUserId() != null) ? "<@" + s.getUserId() + "> " : "";
                tc.sendMessage(mention + prefix + "\n📌 **할 일:** " + s.getTask())
                        .addActionRow(Button.success("complete:" + s.getId(), "✅ 완료"))
                        .queue();
            }
        }
    }

    /**
     * 완료 처리 + DB에서 삭제 (정시 알림 후 ✅ 완료 버튼용).
     */
    @Transactional
    public String completeAndRemove(Long scheduleId) {
        return scheduleRepository.findById(scheduleId).map(s -> {
            String task = s.getTask();
            scheduleRepository.delete(s);
            return task;
        }).orElse(null);
    }

    /**
     * 매일 새벽 3시: 정시 알림이 지났지만 완료 처리되지 않은 1회성 일정을 정리.
     * (사용자가 ✅ 완료 버튼을 누르지 않은 경우에도 다음날엔 사라지도록)
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    public void cleanupExpiredSchedules() {
        LocalDateTime threshold = LocalDateTime.now().minusHours(6);
        List<Schedule> stale = scheduleRepository.findAllByTargetTimeBefore(threshold).stream()
                .filter(s -> !s.isRepeat())
                .toList();
        for (Schedule s : stale) {
            scheduleRepository.delete(s);
        }
        if (!stale.isEmpty()) {
            log.info("🧹 만료된 1회성 일정 {}건 정리 완료", stale.size());
        }
    }

    // =================================================================
    // 🔄 4. 양방향 동기화 (구글 캘린더 → 앱 import)
    // =================================================================

    /**
     * 10분마다 연동된 모든 사용자의 구글 캘린더를 폴링해서,
     * 앱에 아직 없는 일정을 Schedule 로 가져온다(import).
     *
     * 설계 노트:
     *  - **미래 일정만** 가져온다([now, now+30일]). 과거 일정은 cleanupExpiredSchedules 가
     *    매일 정리하므로 import 해봤자 다시 지워져 무의미 + 불필요한 중복을 만든다.
     *  - **googleEventId 로 중복을 막는다.** 앱이 push 한 일정도 같은 ID 를 들고 있으므로
     *    다시 import 되지 않는다(무한 동기화 방지).
     *  - 종일(all-day) 이벤트는 dateTime 이 없고 date 만 있으므로 건너뛴다(시간이 없는 일정).
     *  - import 한 일정은 기본적으로 알림 off 로 둔다(사용자가 원하면 웹에서 켤 수 있음).
     */
    @Scheduled(fixedDelay = 600_000L, initialDelay = 60_000L) // 10분 주기, 기동 1분 후 첫 실행
    @Transactional
    public void importFromGoogleCalendar() {
        List<GoogleToken> connectedUsers = googleTokenRepository.findAll();
        if (connectedUsers.isEmpty()) return;

        LocalDateTime from = LocalDateTime.now();
        LocalDateTime to = from.plusDays(90); // 앞으로 3개월 분량 import
        int totalImported = 0;

        for (GoogleToken token : connectedUsers) {
            String userId = token.getUserId();
            try {
                List<Event> events = googleCalendarService.getEventsBetween(userId, from, to);
                int imported = 0;

                for (Event event : events) {
                    String googleEventId = event.getId();
                    if (googleEventId == null) continue;

                    // 이미 알고 있는 이벤트면 건너뜀 (앱이 push 했거나 이전에 import 한 것)
                    if (scheduleRepository.existsByUserIdAndGoogleEventId(userId, googleEventId)) continue;

                    // 시간 단위 / 종일 분기.
                    //   - 시간 일정: start/end 에 dateTime 이 채워져 있음
                    //   - 종일 일정: start/end 에 date 만 채워져 있음 (dateTime null)
                    LocalDateTime start;
                    LocalDateTime end;
                    boolean allDay;

                    LocalDateTime timed = toLocalDateTime(event.getStart());
                    if (timed != null) {
                        start = timed;
                        end = toLocalDateTime(event.getEnd());
                        allDay = false;
                    } else {
                        java.time.LocalDate startDate = toLocalDate(event.getStart());
                        if (startDate == null) continue; // 알 수 없는 형식이면 건너뜀
                        java.time.LocalDate endDate = toLocalDate(event.getEnd());

                        // 구글 종일 이벤트의 end.date 는 "다음날(exclusive)" 이다.
                        // 예: 5/26 종일 → start.date=5/26, end.date=5/27.
                        // 화면/조회 호환을 위해 종료 시각은 마지막 날 23:59:59 로.
                        start = startDate.atStartOfDay();
                        end = (endDate != null)
                                ? endDate.minusDays(1).atTime(23, 59, 59)
                                : startDate.atTime(23, 59, 59);
                        allDay = true;
                    }

                    String summary = (event.getSummary() != null && !event.getSummary().isBlank())
                            ? event.getSummary() : "(제목 없음)";

                    Schedule s = Schedule.builder()
                            .task(summary)
                            .userId(userId)
                            .targetTime(start)
                            .startTime(start)
                            .endTime(end)
                            .alert24h(false)
                            .alert1h(false)
                            .googleEventId(googleEventId)
                            .allDay(allDay)
                            .build();
                    scheduleRepository.save(s);
                    imported++;
                }

                if (imported > 0) {
                    log.info("🔄 [유저 {}] 구글 캘린더에서 {}건 import 완료", userId, imported);
                    totalImported += imported;
                }
            } catch (Exception e) {
                // 한 유저에서 실패해도 나머지 유저 동기화는 계속 진행
                log.error("🔴 [유저 {}] 구글 캘린더 import 중 오류", userId, e);
            }
        }

        if (totalImported > 0) {
            log.info("🔄 양방향 동기화: 총 {}건 import", totalImported);
        }
    }

    /** 구글 EventDateTime → LocalDateTime (시간 단위). 종일이면 null. */
    private LocalDateTime toLocalDateTime(com.google.api.services.calendar.model.EventDateTime edt) {
        if (edt == null || edt.getDateTime() == null) return null;
        long millis = edt.getDateTime().getValue();
        return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), ZoneId.systemDefault());
    }

    /** 구글 EventDateTime → LocalDate (종일). 시간 단위거나 비어있으면 null. */
    private java.time.LocalDate toLocalDate(com.google.api.services.calendar.model.EventDateTime edt) {
        if (edt == null || edt.getDate() == null) return null;
        // edt.getDate() 는 "yyyy-MM-dd" 형식의 DateTime. toStringRfc3339() 로 안전하게 꺼낸다.
        String raw = edt.getDate().toStringRfc3339();
        // 혹시 시간 정보가 붙어 오더라도 앞 10자(날짜)만 사용.
        if (raw.length() >= 10) raw = raw.substring(0, 10);
        return java.time.LocalDate.parse(raw);
    }
}