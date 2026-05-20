package com.akaide.bot.listener;

import com.akaide.bot.domain.ActiveTime;
import com.akaide.bot.domain.Schedule;
import com.akaide.bot.domain.TokenUsage;
import com.akaide.bot.repository.ActiveTimeRepository;
import com.akaide.bot.repository.ScheduleRepository;
import com.akaide.bot.repository.TargetChannelRepository;
import com.akaide.bot.repository.TokenUsageRepository;
import com.akaide.bot.service.EmbedService;
import com.akaide.bot.service.OAuth2Service;
import com.akaide.bot.service.ScheduleService;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class DiscordBotListener extends ListenerAdapter {

    private final ScheduleService scheduleService;
    private final EmbedService embedService;
    private final OAuth2Service oAuth2Service;

    private final ScheduleRepository scheduleRepository;
    private final TargetChannelRepository targetChannelRepository;
    private final TokenUsageRepository tokenUsageRepository;
    private final ActiveTimeRepository activeTimeRepository;

    private final String adminId;

    public DiscordBotListener(
            ScheduleService scheduleService, EmbedService embedService, OAuth2Service oAuth2Service,
            ScheduleRepository scheduleRepository, TargetChannelRepository targetChannelRepository,
            TokenUsageRepository tokenUsageRepository, ActiveTimeRepository activeTimeRepository,
            @Value("${discord.bot.admin-id}") String adminId) {
        this.scheduleService = scheduleService;
        this.embedService = embedService;
        this.oAuth2Service = oAuth2Service;
        this.scheduleRepository = scheduleRepository;
        this.targetChannelRepository = targetChannelRepository;
        this.tokenUsageRepository = tokenUsageRepository;
        this.activeTimeRepository = activeTimeRepository;
        this.adminId = adminId;
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "일정목록" -> showScheduleList(event);
            case "토큰확인" -> showTokenUsage(event);
            case "채널등록" -> requestApprovalSlash(event);
            case "채널삭제" -> deleteChannel(event);
            case "일정삭제" -> deleteSchedule(event);
            case "활동설정" -> setActivityTime(event);
            case "빈시간확인" -> checkFreeTime(event);
            case "구글연동" -> sendGoogleAuthLink(event);
            case "오늘일정" -> showDaySchedule(event, java.time.LocalDate.now(), "오늘");
            case "내일일정" -> showDaySchedule(event, java.time.LocalDate.now().plusDays(1), "내일");
            case "일정수정" -> editSchedule(event);
        }
    }

    private void showDaySchedule(SlashCommandInteractionEvent event, java.time.LocalDate date, String label) {
        event.deferReply().queue();
        List<Schedule> dbList = scheduleService.getSchedulesForDate(date);
        var googleList = scheduleService.getGoogleEventsForDate(event.getUser().getId(), date);
        event.getHook().sendMessageEmbeds(
                embedService.createDayScheduleEmbed(label, date, dbList, googleList)
        ).queue();
    }

    private void editSchedule(SlashCommandInteractionEvent event) {
        OptionMapping kw = event.getOption("키워드");
        OptionMapping change = event.getOption("변경");
        if (kw == null || change == null) {
            event.reply("❌ 키워드와 변경 내용을 모두 입력해주세요.").setEphemeral(true).queue();
            return;
        }
        event.deferReply().queue();
        scheduleService.editScheduleByNaturalLanguage(kw.getAsString(), change.getAsString(), event.getUser().getId())
                .thenAccept(msg -> event.getHook().sendMessage(msg).queue())
                .exceptionally(e -> {
                    event.getHook().sendMessage("❌ 수정 중 오류가 발생했습니다: " + e.getMessage()).queue();
                    return null;
                });
    }

    private void sendGoogleAuthLink(SlashCommandInteractionEvent event) {
        String authUrl = oAuth2Service.getAuthorizationUrl(event.getUser().getId());
        event.reply("🔗 **Akaide 구글 캘린더 연동**\n아래 링크를 클릭하여 구글 계정에 로그인해 주세요!\n\n" + authUrl)
                .setEphemeral(true).queue();
    }

    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;

        if (targetChannelRepository.existsByChannelId(event.getChannel().getId()) && event.getMessage().getContentRaw().length() >= 3) {
            scheduleService.processSmartMessage(event.getMessage().getContentRaw(), event.getAuthor().getId())
                    .thenAccept(result -> {
                        switch (result.getType()) {
                            case SUCCESS -> event.getChannel().sendMessage("✅ 일정 등록 완료: **" + result.getTask() + "**").queue();
                            case SUGGESTION -> event.getChannel().sendMessageEmbeds(
                                    embedService.createSuggestionEmbed(result.getText(), result.getTask(), result.getStart(), result.getEnd())
                            ).addActionRow(
                                    Button.success("confirm:" + result.getButtonId(), "이대로 등록하기"),
                                    Button.danger("cancel", "다음에 하기")
                            ).queue();
                            case CONFLICT -> event.getChannel().sendMessageEmbeds(
                                    embedService.createConflictEmbed(result.getTask(), result.getStart(), result.getEnd(), result.getConflictDescription())
                            ).addActionRow(
                                    Button.danger("confirm:" + result.getButtonId(), "그래도 등록"),
                                    Button.secondary("cancel", "취소")
                            ).queue();
                            case ERROR -> event.getChannel().sendMessage("🚨 문장을 분석하는 중 오류가 발생했습니다.").queue();
                            case IGNORE -> { /* 무시 */ }
                        }
                    });
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String[] parts = event.getComponentId().split(":");
        String action = parts[0];

        // 💡 1. 지저분한 if-else 구조를 깔끔한 switch문으로 변경 (경고 해결)
        switch (action) {
            case "confirm" -> {
                event.deferEdit().queue();
                scheduleService.confirmRecommendation(parts[1], event.getUser().getId())
                        .thenAccept(task -> event.getHook().editOriginal("✅ 일정이 확정되어 등록되었습니다: **" + task + "**")
                                .setEmbeds().setComponents().queue()) // 💡 2. 람다식 최적화 (경고 해결)
                        .exceptionally(e -> {
                            event.getHook().sendMessage("❌ 요청 처리 중 오류가 발생했습니다.").setEphemeral(true).queue();
                            return null;
                        });
            }
            case "cancel" -> event.editMessage("추천을 취소했습니다.").setEmbeds().setComponents().queue();
            case "approve" -> {
                // parts[1] = 등록할 채널 ID
                String channelId = parts.length > 1 ? parts[1] : null;
                handleAdminApproval(event, true, channelId);
            }
            case "deny" -> {
                String channelId = parts.length > 1 ? parts[1] : null;
                handleAdminApproval(event, false, channelId);
            }
            case "complete" -> {
                // ✅ 정시 알림 완료 버튼: 일정을 완료 처리하고 DB에서 삭제
                if (parts.length < 2) {
                    event.reply("❌ 잘못된 요청입니다.").setEphemeral(true).queue();
                    return;
                }
                try {
                    Long scheduleId = Long.parseLong(parts[1]);
                    String task = scheduleService.completeAndRemove(scheduleId);
                    if (task != null) {
                        event.editMessage("✅ **완료 처리됨** — " + task + " 수고하셨어요!")
                                .setComponents().queue();
                    } else {
                        event.editMessage("ℹ️ 이미 처리되었거나 사라진 일정이에요.")
                                .setComponents().queue();
                    }
                } catch (NumberFormatException nfe) {
                    event.reply("❌ 일정 ID 형식이 올바르지 않습니다.").setEphemeral(true).queue();
                }
            }
        }
    }

    private void setActivityTime(SlashCommandInteractionEvent event) {
        OptionMapping dayOpt = event.getOption("요일");
        OptionMapping startOpt = event.getOption("시작");
        OptionMapping endOpt = event.getOption("종료");

        // 💡 4. NPE(NullPointer) 방지용 안전 장치 추가 (경고 해결)
        if (dayOpt == null || startOpt == null || endOpt == null) {
            event.reply("❌ 필수 입력값이 누락되었습니다.").setEphemeral(true).queue();
            return;
        }

        String dayInput = dayOpt.getAsString();
        int start = startOpt.getAsInt();
        int end = endOpt.getAsInt();

        if (start < 0 || start > 23 || end < 0 || end >= 24 || start >= end) {
            event.reply("❌ 올바른 시간을 입력해주세요 (0~23시, 시작 < 종료).").setEphemeral(true).queue();
            return;
        }

        List<String> targetDays = new ArrayList<>();
        if (dayInput.equals("평일")) targetDays.addAll(List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY"));
        else if (dayInput.equals("주말")) targetDays.addAll(List.of("SATURDAY", "SUNDAY"));
        else targetDays.add(convertToDayOfWeek(dayInput));

        for (String day : targetDays) activeTimeRepository.save(new ActiveTime(day, start, end));
        event.reply("📅 **" + dayInput + "** 활동 시간이 **" + start + "시 ~ " + end + "시**로 설정되었습니다.").queue();
    }

    private void checkFreeTime(SlashCommandInteractionEvent event) {
        event.deferReply().queue();
        String analysisResult = scheduleService.analyzeFreeTime(LocalDateTime.now(), event.getUser().getId());
        event.getHook().sendMessageEmbeds(
                embedService.createFreeTimeEmbed(analysisResult, event.getJDA().getSelfUser().getAvatarUrl())
        ).queue();
    }

    private void showScheduleList(SlashCommandInteractionEvent event) {
        List<Schedule> list = scheduleRepository.findAll();
        if (list.isEmpty()) { event.reply("📭 저장된 일정이 없습니다.").queue(); return; }
        event.replyEmbeds(embedService.createScheduleListEmbed(list)).queue();
    }

    private void showTokenUsage(SlashCommandInteractionEvent event) {
        TokenUsage usage = tokenUsageRepository.findById("total_usage").orElse(new TokenUsage("total_usage", 0, 0, 0));
        event.replyEmbeds(embedService.createTokenUsageEmbed(usage)).queue();
    }

    private void deleteChannel(SlashCommandInteractionEvent event) {
        if (!event.getUser().getId().equals(adminId)) { event.reply("❌ 권한 없음").setEphemeral(true).queue(); return; }
        targetChannelRepository.deleteById(event.getChannel().getId());
        event.reply("🚫 자동 분석 기능 해제 완료").queue();
    }

    private void requestApprovalSlash(SlashCommandInteractionEvent event) {
        String channelId = event.getChannel().getId();
        // 💡 5. 중첩 람다식 최적화 (경고 해결)
        event.getJDA().retrieveUserById(adminId).queue(admin ->
                admin.openPrivateChannel().queue(dm ->
                        dm.sendMessage("🔔 **승인 요청:** " + event.getChannel().getName() + " (ID: " + channelId + ")")
                                .addActionRow(Button.success("approve:" + channelId, "승인"), Button.danger("deny:" + channelId, "거절"))
                                .queue()
                )
        );
        event.reply("⏳ 관리자 승인 대기 중...").queue();
    }

    private void deleteSchedule(SlashCommandInteractionEvent event) {
        OptionMapping keywordOpt = event.getOption("키워드");
        if (keywordOpt == null) {
            event.reply("❌ 삭제할 키워드를 입력해주세요.").setEphemeral(true).queue();
            return;
        }

        try {
            scheduleService.deleteScheduleByKeyword(keywordOpt.getAsString());
            event.reply("🗑️ 삭제 완료").queue();
        } catch (Exception e) {
            event.reply("❌ 오류가 발생했습니다.").setEphemeral(true).queue();
        }
    }

    private void handleAdminApproval(ButtonInteractionEvent event, boolean approve, String channelId) {
        if (!event.getUser().getId().equals(adminId)) {
            event.reply("❌ 권한이 없습니다.").setEphemeral(true).queue();
            return;
        }

        if (channelId == null || channelId.isBlank()) {
            event.editMessage("❌ 채널 ID가 누락되어 처리할 수 없습니다.").setComponents().queue();
            return;
        }

        if (approve) {
            // 1) DB에 채널 저장 (이미 있으면 덮어쓰기)
            event.getJDA().retrieveUserById(adminId); // (no-op, JDA 워밍업)
            net.dv8tion.jda.api.entities.channel.middleman.MessageChannel ch =
                    event.getJDA().getChannelById(net.dv8tion.jda.api.entities.channel.middleman.MessageChannel.class, channelId);
            String channelName = (ch != null) ? ch.getName() : "(이름 알 수 없음)";

            targetChannelRepository.save(new com.akaide.bot.domain.TargetChannel(channelId, channelName));

            // 2) 관리자에게 결과 표시
            event.editMessage("✅ **승인 완료** — 채널 `" + channelName + "` (`" + channelId + "`) 자동 분석 활성화")
                    .setComponents().queue();

            // 3) 신청한 채널에도 알림 보내기
            if (ch != null) {
                ch.sendMessage("🎉 이 채널이 **자동 분석 채널**로 등록되었습니다! 이제 메시지를 보내면 AI가 자동으로 일정을 잡아드려요.").queue();
            }
        } else {
            event.editMessage("❌ 요청 거절됨 (채널 `" + channelId + "`)").setComponents().queue();
        }
    }

    private String convertToDayOfWeek(String input) {
        return switch (input.substring(0, 1)) {
            case "월" -> "MONDAY"; case "화" -> "TUESDAY"; case "수" -> "WEDNESDAY";
            case "목" -> "THURSDAY"; case "금" -> "FRIDAY"; case "토" -> "SATURDAY";
            case "일" -> "SUNDAY"; default -> input.toUpperCase();
        };
    }
}