package com.akaide.bot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.akaide.bot.domain.TokenUsage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import com.akaide.bot.repository.TokenUsageRepository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class GeminiService {

    // gemini-2.5-flash 는 v1beta 엔드포인트에서 안정적으로 제공된다.
    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=";

    @Value("${gemini.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TokenUsageRepository tokenUsageRepository;

    public GeminiService(TokenUsageRepository tokenUsageRepository) {
        this.tokenUsageRepository = tokenUsageRepository;
    }

    public String analyzeMessage(String userMessage) {
        String url = GEMINI_URL + apiKey;
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // 프롬프트는 동일하게 유지
        String prompt = "너는 똑똑한 일정 관리 비서 'Akaide'야. 아래 규칙에 따라 반드시 JSON으로만 응답해.\n\n" +
                "1. 현재 시간 기준: " + currentTime + "\n" +
                // "2. 가용 시간 정보: " + freeTimeData + "\n" (호출부에서 덧붙임)
                "3. 일정 유형별 응답 형식:\n" +
                "   - [즉시 등록]: {'task': '..', 'time': 'ISO_8601', 'is_suggestion': false, 'alert24h': true/false, 'alert1h': true/false}\n" +
                "   - [기간제 일정]: {'task': '..', 'start': 'ISO_8601', 'end': 'ISO_8601', 'is_suggestion': false}\n" +
                "   - [시간 추천]: {'is_suggestion': true, 'suggestion_text': '추천 이유', 'task': '할 일', 'start': 'ISO_8601', 'end': 'ISO_8601'}\n" +
                "   - [반복 일정]: {'task': '..', 'is_repeat': true, 'repeat_rule': '규칙', 'time': 'ISO_8601'}\n\n" +
                "4. 일정이 아니거나 이해할 수 없으면 무조건 {'task': 'unknown'} 만 반환해.\n\n" +
                "문장: " + userMessage;

        try {
            Map<String, Object> textPart = Map.of("text", prompt);
            Map<String, Object> parts = Map.of("parts", List.of(textPart));
            Map<String, Object> requestBody = Map.of("contents", List.of(parts));

            String requestJson = objectMapper.writeValueAsString(requestBody);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);
            ResponseEntity<String> responseEntity = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            String response = responseEntity.getBody();
            JsonNode rootNode = objectMapper.readTree(response);

            // 토큰 사용량 기록
            JsonNode usage = rootNode.path("usageMetadata");
            if (!usage.isMissingNode()) {
                updateTokenUsage(
                        usage.path("promptTokenCount").asLong(),
                        usage.path("candidatesTokenCount").asLong(),
                        usage.path("totalTokenCount").asLong()
                );
            }

            String extractedText = rootNode.path("candidates").get(0)
                    .path("content").path("parts").get(0)
                    .path("text").asText();

            // 🛡️ [핵심 방어 로직] AI가 마크다운이나 잡담을 섞었을 때 순수 JSON만 추출
            return extractJson(extractedText);

        } catch (HttpStatusCodeException e) {
            log.error("🔴 Gemini API 에러: {}", e.getResponseBodyAsString());
            return "{\"task\": \"error\", \"message\": \"API 호출 실패\"}";
        } catch (Exception e) {
            log.error("🔴 Gemini 응답 분석 실패", e);
            return "{\"task\": \"error\", \"message\": \"응답 분석 실패\"}";
        }
    }

    /**
     * 자연어 변경 지시문을 받아서 새로운 일정 시간/제목을 JSON으로 산출합니다.
     * 응답 형식: {"new_task": "...", "new_start": "ISO_8601", "new_end": "ISO_8601 or null"}
     * 변경할 필드만 채우고 나머지는 null로 둡니다.
     */
    public String analyzeEditInstruction(String currentTask, LocalDateTime currentTime, String userInstruction) {
        String url = GEMINI_URL + apiKey;
        String currentTimeStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String currentScheduleStr = (currentTime != null) ? currentTime.toString() : "(없음)";

        String prompt = "너는 일정 수정 도우미야. 아래 정보를 보고 변경된 새 정보를 JSON으로만 응답해.\n\n" +
                "현재 시간: " + currentTimeStr + "\n" +
                "기존 일정 제목: " + currentTask + "\n" +
                "기존 일정 시간: " + currentScheduleStr + "\n" +
                "변경 지시: " + userInstruction + "\n\n" +
                "응답 형식 (변경되지 않은 필드는 null):\n" +
                "{\"new_task\": \"새 제목 또는 null\", \"new_start\": \"ISO_8601 또는 null\", \"new_end\": \"ISO_8601 또는 null\"}\n" +
                "이해할 수 없으면 {\"error\": \"unknown\"} 으로 응답.";

        try {
            Map<String, Object> textPart = Map.of("text", prompt);
            Map<String, Object> parts = Map.of("parts", List.of(textPart));
            Map<String, Object> requestBody = Map.of("contents", List.of(parts));

            String requestJson = objectMapper.writeValueAsString(requestBody);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);
            ResponseEntity<String> responseEntity = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            JsonNode rootNode = objectMapper.readTree(responseEntity.getBody());

            JsonNode usage = rootNode.path("usageMetadata");
            if (!usage.isMissingNode()) {
                updateTokenUsage(
                        usage.path("promptTokenCount").asLong(),
                        usage.path("candidatesTokenCount").asLong(),
                        usage.path("totalTokenCount").asLong()
                );
            }

            String text = rootNode.path("candidates").get(0)
                    .path("content").path("parts").get(0)
                    .path("text").asText("");
            return extractJson(text);
        } catch (Exception e) {
            log.warn("⚠️ 수정 분석 실패: {}", e.getMessage());
            return "{\"error\": \"unknown\"}";
        }
    }

    /**
     * 가용 시간 정보를 바탕으로 자유 형식의 한 줄 추천 문구를 생성합니다.
     * 실패 시 빈 문자열을 반환해서 호출부에서 안전하게 처리할 수 있게 합니다.
     */
    public String generateFreeTimeRecommendation(String freeSlotsSummary) {
        String url = GEMINI_URL + apiKey;

        String prompt = "너는 사용자의 일정 비서 'Akaide'야.\n" +
                "아래 빈 시간대 정보를 보고, 그 시간에 무엇을 하면 좋을지 따뜻하고 짧은 한 문장(40자 이내)으로 추천해줘.\n" +
                "공부, 운동, 휴식, 취미, 자기계발 등 다양한 활동을 균형 있게 제안해.\n" +
                "JSON이나 마크다운 없이, 따옴표 없이, 추천 문구 한 줄만 출력해.\n\n" +
                "[빈 시간대 정보]\n" + freeSlotsSummary;

        try {
            Map<String, Object> textPart = Map.of("text", prompt);
            Map<String, Object> parts = Map.of("parts", List.of(textPart));
            Map<String, Object> requestBody = Map.of("contents", List.of(parts));

            String requestJson = objectMapper.writeValueAsString(requestBody);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);
            ResponseEntity<String> responseEntity = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            JsonNode rootNode = objectMapper.readTree(responseEntity.getBody());

            // 토큰 사용량도 누적 기록
            JsonNode usage = rootNode.path("usageMetadata");
            if (!usage.isMissingNode()) {
                updateTokenUsage(
                        usage.path("promptTokenCount").asLong(),
                        usage.path("candidatesTokenCount").asLong(),
                        usage.path("totalTokenCount").asLong()
                );
            }

            String text = rootNode.path("candidates").get(0)
                    .path("content").path("parts").get(0)
                    .path("text").asText("").trim();

            // 양 끝의 따옴표/마크다운 제거
            text = text.replaceAll("^[\"'`*_>\\s]+", "").replaceAll("[\"'`*_\\s]+$", "");
            return text;
        } catch (Exception e) {
            log.warn("⚠️ 추천 문구 생성 실패: {}", e.getMessage());
            return "";
        }
    }

    private String extractJson(String text) {
        int startIndex = text.indexOf('{');
        int endIndex = text.lastIndexOf('}');

        if (startIndex != -1 && endIndex != -1 && startIndex < endIndex) {
            return text.substring(startIndex, endIndex + 1);
        }
        // 중괄호가 없으면 파싱 실패로 간주하고 unknown 처리
        return "{\"task\": \"unknown\"}";
    }

    /**
     * 사용자가 BLMS 강의실 페이지에서 복사한 거친 텍스트를 받아
     * 학사 항목(과제/토론/공지/주차학습)을 JSON 배열로 추출한다.
     *
     * 응답 형식: [{kind, title, courseName, startAt, endAt, status, sourceCode}]
     *   - kind: ASSIGNMENT / FORUM / NOTICE / LESSON / UNKNOWN
     *   - 일시는 ISO_8601 ("yyyy-MM-ddTHH:mm:ss") 또는 null
     *   - sourceCode: 텍스트에 ASMNT_xxx / FORUM_xxx 같은 식별자가 있으면 그 값
     *
     * 추출 실패/이해 불가 시 빈 배열 "[]" 반환.
     */
    public String analyzeBlmsText(String text) {
        String url = GEMINI_URL + apiKey;
        String currentTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        String prompt = "너는 한국 대학교 사이버캠퍼스(LMS) 페이지 텍스트에서 " +
                "**마감일이 있는 과제/토론/공지**만 골라 JSON 배열로 출력하는 추출기야.\n\n" +
                "현재 시간: " + currentTime + "\n\n" +
                "각 원소 형식: {" +
                "\"kind\":\"ASSIGNMENT|FORUM|NOTICE\"," +
                "\"title\":\"항목 제목 (예: '중간고사 과제', '12차토론')\"," +
                "\"courseName\":\"강의명 또는 null\"," +
                "\"startAt\":\"ISO_8601 또는 null\"," +
                "\"endAt\":\"마감/종료 ISO_8601 (필수, 없으면 그 항목 자체를 제외)\"," +
                "\"status\":\"상태 텍스트(예: '미제출', '대기', '진행중') 또는 null\"," +
                "\"sourceCode\":\"ASMNT_xxx / FORUM_xxx 같은 식별자 또는 null\"" +
                "}\n\n" +
                "필수 규칙 — 위반 시 그 원소 제외:\n" +
                "1. **kind 는 반드시 ASSIGNMENT / FORUM / NOTICE 중 하나**. 주차별 학습/온라인 강의/메뉴/네비게이션은 절대 포함하지 마.\n" +
                "   - '과제', '제출', 'ASMNT_' → ASSIGNMENT\n" +
                "   - '토론', 'FORUM_' → FORUM\n" +
                "   - '공지', '알림' → NOTICE\n" +
                "   - 'N주차', '학습', '완료율', 'LESN_', '진도율', '강의 시청', '동영상' → 제외\n" +
                "2. **endAt(마감일)이 없으면 그 항목 제외**. 단순히 카테고리 이름이나 메뉴는 마감이 없으니 자동으로 빠짐.\n" +
                "3. **endAt 이 현재 시간(" + currentTime + ") 보다 과거인 항목은 제외**. 이미 지난 일정은 등록할 필요 없음.\n" +
                "4. **이미 제출 완료/이수 완료된 항목은 제외**. status 에 '제출함', '완료' 등이 명시되면 빼.\n" +
                "5. **사이트 메뉴/푸터/네비게이션 텍스트는 제외**. 'My페이지', '강의실', '학습활동', '로그아웃', '백석대학교' 등은 무시.\n" +
                "6. 날짜는 다양한 한국어 형식(2026.05.18(13:00), 2026-05-18 등)을 모두 ISO_8601 로 변환.\n" +
                "7. 같은 항목의 중복 표현은 하나로 합쳐.\n" +
                "8. 적합한 항목이 하나도 없으면 빈 배열 [] 반환.\n" +
                "9. JSON 배열만, 다른 설명/마크다운/코드블록 없이.\n\n" +
                "텍스트:\n" + text;

        try {
            Map<String, Object> textPart = Map.of("text", prompt);
            Map<String, Object> parts = Map.of("parts", List.of(textPart));
            Map<String, Object> requestBody = Map.of("contents", List.of(parts));

            String requestJson = objectMapper.writeValueAsString(requestBody);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(requestJson, headers);
            ResponseEntity<String> responseEntity = restTemplate.exchange(url, HttpMethod.POST, entity, String.class);

            JsonNode rootNode = objectMapper.readTree(responseEntity.getBody());
            JsonNode usage = rootNode.path("usageMetadata");
            if (!usage.isMissingNode()) {
                updateTokenUsage(
                        usage.path("promptTokenCount").asLong(),
                        usage.path("candidatesTokenCount").asLong(),
                        usage.path("totalTokenCount").asLong()
                );
            }

            String content = rootNode.path("candidates").get(0)
                    .path("content").path("parts").get(0)
                    .path("text").asText("");

            return extractJsonArray(content);
        } catch (Exception e) {
            log.warn("⚠️ BLMS 텍스트 분석 실패: {}", e.getMessage());
            return "[]";
        }
    }

    /** JSON 배열 부분만 추출. 실패 시 빈 배열. */
    private String extractJsonArray(String text) {
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start != -1 && end != -1 && start < end) {
            return text.substring(start, end + 1);
        }
        return "[]";
    }

    private void updateTokenUsage(long prompt, long candidate, long total) {
        TokenUsage usage = tokenUsageRepository.findById("total_usage")
                .orElse(TokenUsage.builder().id("total_usage").totalPromptTokens(0L).totalCandidateTokens(0L).totalTokens(0L).build());
        usage.setTotalPromptTokens(usage.getTotalPromptTokens() + prompt);
        usage.setTotalCandidateTokens(usage.getTotalCandidateTokens() + candidate);
        usage.setTotalTokens(usage.getTotalTokens() + total);
        tokenUsageRepository.save(usage);
    }
}