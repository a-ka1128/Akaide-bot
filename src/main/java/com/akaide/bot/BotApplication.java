package com.akaide.bot;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@SpringBootApplication
@EnableScheduling
public class BotApplication {
	public static void main(String[] args) {
		SpringApplication.run(BotApplication.class, args);
	}

	@PostConstruct
	public void init() {
		// 앱 시작 시 타임존을 한국 시간으로 강제 고정합니다.
		java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Asia/Seoul"));
		System.out.println("⏰ 타임존 설정 완료: " + java.time.LocalDateTime.now());
	}
}




