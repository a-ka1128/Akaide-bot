package com.akaide.bot.service;

import com.akaide.bot.domain.BlmsItem;
import com.akaide.bot.domain.Schedule;
import com.akaide.bot.repository.BlmsItemRepository;
import com.akaide.bot.repository.ScheduleRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 백석대 BLMS 페이지에서 추출한 학사 항목을 처리한다.
 *
 * 두 가지 경로 지원:
 *  1. importFromHtml(html) — 사용자가 F12 → Copy outerHTML 로 복사한 HTML 을 Jsoup 으로 파싱
 *  2. importFromText(text) — 단순 텍스트(Ctrl+A 복사)를 Gemini 에 보내 추출
 *
 * 추출된 항목은 (userId, sourceCode) 기준 upsert.
 */
@Slf4j
@Service
public class BlmsService {

    private final BlmsItemRepository blmsItemRepository;
    private final ScheduleRepository scheduleRepository;
    private final GeminiService geminiService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BlmsService(BlmsItemRepository blmsItemRepository,
                       ScheduleRepository scheduleRepository,
                       GeminiService geminiService) {
        this.blmsItemRepository = blmsItemRepository;
        this.scheduleRepository = scheduleRepository;
        this.geminiService = geminiService;
    }

    // =================================================================
    // HTML 파싱 (Phase B)
    // =================================================================

    /**
     * 사용자가 복사한 BLMS HTML 에서 .card 단위로 과제/토론을 추출.
     * BLMS 의 HTML 구조 (Semantic UI 기반)를 가정한다.
     */
    @Transactional
    public List<BlmsItem> importFromHtml(String html, String userId) {
        if (html == null || html.isBlank()) return List.of();

        Document doc = Jsoup.parse(html);
        List<BlmsItem> results = new ArrayList<>();

        // ASMNT/FORUM 카드만 가져옴. 주차별 학습(LESSON) 은 사용자가 일정으로 등록할 가치가 적어 제외.
        Elements cards = doc.select(".card");
        for (Element card : cards) {
            BlmsItem item = parseAssignmentOrForumCard(card, userId);
            if (item != null) results.add(item);
        }

        return upsertAll(filterValid(results));
    }

    /** 카드 하나에서 과제/토론 정보를 추출. 매칭 실패 시 null. */
    private BlmsItem parseAssignmentOrForumCard(Element card, String userId) {
        Element titleBox = card.selectFirst(".title-box");
        if (titleBox == null) return null;

        Element label = titleBox.selectFirst("label");
        Element link = titleBox.selectFirst("a.header-icon, a.header.header-icon");
        if (label == null || link == null) return null;

        String labelText = label.text().trim();      // "과제", "토론", "공지" ...
        String title = link.text().trim();
        String onclick = link.attr("href") + " " + link.attr("onclick");
        String kind = mapKind(labelText);

        // sourceCode: "asmntView('ASMNT_xxx')" 또는 "forumView('FORUM_xxx')" 추출
        String sourceCode = extractSourceCode(onclick);

        // 상태 (예: "과제를 제출하지 않았습니다")
        Element status = card.selectFirst(".process-bar .bar-softgrey, .process-bar li");
        String statusText = (status != null) ? status.text().trim() : null;

        // 시작/종료 — "제출기간" 또는 "토론기간" fields 안의 <i>2026.06.01(00:00) ~ 2026.06.10(12:00)</i>
        LocalDateTime[] range = extractDateRange(card);

        return BlmsItem.builder()
                .userId(userId)
                .kind(kind)
                .title(title)
                .status(statusText)
                .sourceCode(sourceCode)
                .startAt(range[0])
                .endAt(range[1])
                .done(statusText != null && (statusText.contains("제출") && !statusText.contains("하지 않")))
                .build();
    }

    /** 주차별 학습 카드 파서. */
    private BlmsItem parseLessonCard(Element lesson, String userId) {
        Element section = lesson.selectFirst("section");
        if (section == null) return null;
        String title = section.text().trim();         // "12주차"
        if (!title.matches(".*\\d+\\s*주차.*")) return null;

        // 식별자 — id="dropdown_LESN_xxx" / id="LESN_xxx"
        String id = lesson.id();
        String sourceCode = null;
        if (id != null && id.contains("LESN_")) {
            sourceCode = id.substring(id.indexOf("LESN_"));
        }

        // 기간
        LocalDateTime[] range = extractDateRange(lesson);

        return BlmsItem.builder()
                .userId(userId)
                .kind("LESSON")
                .title(title)
                .sourceCode(sourceCode)
                .startAt(range[0])
                .endAt(range[1])
                .build();
    }

    /** 카드 안의 "기간" 또는 "제출기간" 표시에서 시작/종료 LocalDateTime 추출. */
    private LocalDateTime[] extractDateRange(Element scope) {
        LocalDateTime[] result = new LocalDateTime[]{null, null};

        // label-title-lg 가 "제출기간"/"토론기간"/"기간" 인 형제 i 또는 span 에서 텍스트 추출
        Elements fields = scope.select(".inline.field, .fields");
        for (Element f : fields) {
            String lbl = f.selectFirst("label, .label-title-lg") != null
                    ? f.selectFirst("label, .label-title-lg").text() : "";
            if (lbl.contains("기간")) {
                String body = f.text().replace(lbl, "").trim();
                LocalDateTime[] r = parseRange(body);
                if (r[0] != null || r[1] != null) {
                    return r;
                }
            }
        }

        // 그래도 못 찾았으면 카드 전체 텍스트에서 처음 발견되는 범위
        return parseRange(scope.text());
    }

    /** "2026.05.18(13:00) ~ 2026.05.24(23:59)" 또는 "2026.05.18 ~ 2026.05.24" 같은 문자열 파싱. */
    private LocalDateTime[] parseRange(String raw) {
        if (raw == null) return new LocalDateTime[]{null, null};
        // 두 개의 날짜 토큰을 정규식으로 찾기
        Pattern p = Pattern.compile(
                "(\\d{4})[.-](\\d{1,2})[.-](\\d{1,2})(?:\\(?(\\d{1,2}):(\\d{1,2})\\)?)?"
        );
        Matcher m = p.matcher(raw);
        LocalDateTime first = null, second = null;
        if (m.find()) {
            first = build(m);
            if (m.find()) {
                second = build(m);
            }
        }
        return new LocalDateTime[]{first, second};
    }

    private LocalDateTime build(Matcher m) {
        try {
            int y = Integer.parseInt(m.group(1));
            int mo = Integer.parseInt(m.group(2));
            int d = Integer.parseInt(m.group(3));
            int h = m.group(4) != null ? Integer.parseInt(m.group(4)) : 0;
            int mi = m.group(5) != null ? Integer.parseInt(m.group(5)) : 0;
            return LocalDateTime.of(y, mo, d, h, mi);
        } catch (Exception e) {
            return null;
        }
    }

    /** "asmntView('ASMNT_xxx')" 같은 문자열에서 코드 추출. */
    private String extractSourceCode(String s) {
        if (s == null) return null;
        Pattern p = Pattern.compile("(ASMNT_[A-Za-z0-9]+|FORUM_[A-Za-z0-9]+|LESN_[A-Za-z0-9]+)");
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private String mapKind(String labelText) {
        if (labelText == null) return "UNKNOWN";
        if (labelText.contains("과제")) return "ASSIGNMENT";
        if (labelText.contains("토론")) return "FORUM";
        if (labelText.contains("공지")) return "NOTICE";
        if (labelText.contains("주차")) return "LESSON";
        return "UNKNOWN";
    }

    // =================================================================
    // 텍스트 → Gemini 분석 (Phase A)
    // =================================================================

    /**
     * 사용자가 복사한 텍스트를 Gemini 에 보내 항목 배열을 받아 저장.
     */
    @Transactional
    public List<BlmsItem> importFromText(String text, String userId) {
        if (text == null || text.isBlank()) return List.of();

        String json = geminiService.analyzeBlmsText(text);
        List<BlmsItem> parsed = new ArrayList<>();
        try {
            JsonNode arr = objectMapper.readTree(json);
            if (!arr.isArray()) return List.of();

            for (JsonNode n : arr) {
                BlmsItem item = BlmsItem.builder()
                        .userId(userId)
                        .kind(text(n, "kind", "UNKNOWN"))
                        .title(text(n, "title", null))
                        .courseName(text(n, "courseName", null))
                        .status(text(n, "status", null))
                        .sourceCode(text(n, "sourceCode", null))
                        .startAt(parseIso(text(n, "startAt", null)))
                        .endAt(parseIso(text(n, "endAt", null)))
                        .build();
                if (item.getTitle() != null && !item.getTitle().isBlank()) {
                    parsed.add(item);
                }
            }
        } catch (Exception e) {
            log.warn("BLMS Gemini JSON 파싱 실패", e);
        }

        return upsertAll(filterValid(parsed));
    }

    /**
     * 잡음/주차 학습/시간 미상/지난 항목을 제거.
     *
     * 제외 조건:
     *  - kind 가 ASSIGNMENT/FORUM/NOTICE 가 아닌 것 (LESSON, UNKNOWN 등)
     *  - endAt 이 null (마감일이 있어야 일정으로 의미가 있음)
     *  - endAt 이 현재 시각보다 과거 (이미 지난 일정)
     *  - 제목이 너무 짧거나 비어있음
     *  - 제목에 메뉴/네비게이션 키워드가 포함됨
     *  - status 가 명백히 완료를 의미함
     */
    private List<BlmsItem> filterValid(List<BlmsItem> items) {
        LocalDateTime now = LocalDateTime.now();
        List<BlmsItem> out = new ArrayList<>();
        for (BlmsItem it : items) {
            String kind = it.getKind() == null ? "" : it.getKind();
            if (!kind.equals("ASSIGNMENT") && !kind.equals("FORUM") && !kind.equals("NOTICE")) continue;
            if (it.getEndAt() == null) continue;
            if (it.getEndAt().isBefore(now)) continue;

            String title = it.getTitle() == null ? "" : it.getTitle().trim();
            if (title.length() < 2) continue;
            if (isMenuLike(title)) continue;

            String status = it.getStatus() == null ? "" : it.getStatus();
            if (status.contains("제출함") || status.equals("완료") || status.contains("이수 완료")) continue;

            out.add(it);
        }
        return out;
    }

    private boolean isMenuLike(String title) {
        String[] menus = {
                "강의실", "학습활동", "성적", "마이페이지", "로그아웃", "백석대",
                "수강신청", "공지사항 목록", "전체 보기", "더보기", "메뉴"
        };
        for (String m : menus) {
            if (title.contains(m)) return true;
        }
        return false;
    }

    private String text(JsonNode n, String field, String defaultValue) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) return defaultValue;
        String s = v.asText("").trim();
        return s.isBlank() ? defaultValue : s;
    }

    private LocalDateTime parseIso(String s) {
        if (s == null) return null;
        try {
            return LocalDateTime.parse(s);
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(s, DateTimeFormatter.ISO_DATE_TIME);
            } catch (Exception ex) {
                return null;
            }
        }
    }

    // =================================================================
    // 공통 upsert + 조회 + Schedule 연동
    // =================================================================

    /** sourceCode 기준 upsert. null 인 경우는 그냥 새로 저장. */
    @Transactional
    public List<BlmsItem> upsertAll(List<BlmsItem> items) {
        List<BlmsItem> saved = new ArrayList<>();
        for (BlmsItem item : items) {
            BlmsItem persisted;
            if (item.getSourceCode() != null) {
                persisted = blmsItemRepository
                        .findByUserIdAndSourceCode(item.getUserId(), item.getSourceCode())
                        .map(existing -> {
                            existing.setKind(item.getKind());
                            existing.setTitle(item.getTitle());
                            existing.setCourseName(item.getCourseName());
                            existing.setStartAt(item.getStartAt());
                            existing.setEndAt(item.getEndAt());
                            existing.setStatus(item.getStatus());
                            existing.setDone(item.isDone());
                            return blmsItemRepository.save(existing);
                        })
                        .orElseGet(() -> blmsItemRepository.save(item));
            } else {
                persisted = blmsItemRepository.save(item);
            }
            saved.add(persisted);
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<BlmsItem> listByUser(String userId) {
        return blmsItemRepository.findAllByUserIdOrderByEndAtAsc(userId);
    }

    @Transactional
    public boolean deleteItem(Long id, String userId) {
        return blmsItemRepository.findById(id)
                .filter(it -> userId.equals(it.getUserId()))
                .map(it -> { blmsItemRepository.delete(it); return true; })
                .orElse(false);
    }

    /**
     * BlmsItem 을 Schedule 로 변환해 등록.
     * endAt 이 있으면 마감일을 일정 시각으로, 없으면 startAt 사용.
     */
    @Transactional
    public Schedule convertToSchedule(Long itemId, String userId) {
        BlmsItem item = blmsItemRepository.findById(itemId)
                .filter(it -> userId.equals(it.getUserId()))
                .orElseThrow(() -> new IllegalArgumentException("항목을 찾을 수 없습니다."));

        LocalDateTime target = item.getEndAt() != null ? item.getEndAt() : item.getStartAt();
        if (target == null) {
            throw new IllegalStateException("이 항목에는 시간 정보가 없어 일정으로 등록할 수 없어요.");
        }

        String prefix = switch (item.getKind() != null ? item.getKind() : "UNKNOWN") {
            case "ASSIGNMENT" -> "[과제] ";
            case "FORUM" -> "[토론] ";
            case "NOTICE" -> "[공지] ";
            case "LESSON" -> "[학습] ";
            default -> "";
        };

        Schedule s = Schedule.builder()
                .task(prefix + item.getTitle())
                .userId(userId)
                .targetTime(target)
                .startTime(target)
                .endTime(item.getEndAt())
                .alert24h(true)
                .alert1h(true)
                .build();
        Schedule saved = scheduleRepository.save(s);

        item.setLinkedScheduleId(saved.getId());
        blmsItemRepository.save(item);
        return saved;
    }
}
