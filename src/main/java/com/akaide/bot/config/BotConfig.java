package com.akaide.bot.config;

import com.akaide.bot.listener.DiscordBotListener;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class BotConfig {

    @Value("${discord.bot.token}")
    private String token;

    // 스프링이 이 메서드를 실행해 JDA 객체를 만들고 빈(Bean)으로 등록합니다.

    @Bean
    public JDA jda(DiscordBotListener listener) {
        JDA jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MESSAGES)
                .addEventListeners(listener)
                .build();

        // 슬래시 명령어 등록 (전체 명령어 리스트)
        jda.updateCommands().addCommands(
                Commands.slash("일정목록", "현재 저장된 모든 일정을 확인합니다."),
                Commands.slash("토큰확인", "제미나이 AI 토큰 누적 사용량을 확인합니다."),
                Commands.slash("채널등록", "이 채널을 자동 분석 채널로 등록 요청합니다."),
                Commands.slash("채널삭제", "이 채널의 자동 분석 기능을 해제합니다."),
                Commands.slash("일정삭제", "특정 키워드가 포함된 일정을 삭제합니다.")
                        .addOption(OptionType.STRING, "키워드", "삭제할 일정의 키워드", true),
                Commands.slash("활동설정", "요일별 활동 기준 시간을 설정합니다.")
                        .addOption(OptionType.STRING, "요일", "월~일 또는 평일/주말", true)
                        .addOption(OptionType.INTEGER, "시작", "시작 시간 (0-23시)", true)
                        .addOption(OptionType.INTEGER, "종료", "종료 시간 (0-23시)", true),
                Commands.slash("빈시간확인", "오늘의 빈 시간을 분석하여 추천받습니다."),
                Commands.slash("구글연동", "내 구글 계정을 연동하여 캘린더 기능을 활성화합니다."),
                Commands.slash("오늘일정", "오늘 등록된 모든 일정을 시간순으로 보여줍니다."),
                Commands.slash("내일일정", "내일 등록된 모든 일정을 시간순으로 보여줍니다."),
                Commands.slash("일정수정", "자연어로 일정을 수정합니다.")
                        .addOption(OptionType.STRING, "키워드", "수정할 일정의 키워드", true)
                        .addOption(OptionType.STRING, "변경", "어떻게 바꿀지 자연어로 입력 (예: '내일 오후 3시로')", true)
        ).queue();

        return jda;
    }


}