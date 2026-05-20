# Akaide — Discord 봇 연동 AI 일정 관리 시스템 중간 보고서

**작성일** 2026-05-18
**작성자** 아카 (acku165@gmail.com)

---

## 1. 프로젝트 개요

### 1.1 동기와 목적

기존의 일정 관리 도구는 두 가지 한계를 가진다. 첫째, 정해진 폼에 시간과 제목을 정확히 입력해야 하므로 일상에서 빠르게 떠오른 일정을 등록하는 데 마찰이 크다. 둘째, 사용자가 가용한 시간을 직접 계산해야 한다는 점이다. 본 프로젝트는 자연어 입력과 AI 기반 분석을 통해 이 두 문제를 해결하는 일정 관리 시스템 *Akaide* 를 구현하는 것을 목표로 한다.

Akaide는 다음 두 가지 인터페이스를 제공한다.

1. **Discord 봇** — 평소 사용하는 메신저에서 자연어로 일정을 등록·조회하고 알림을 받는다.
2. **웹 대시보드** — 캘린더와 통계, 빈 시간 시각화를 통해 일정을 시각적으로 관리한다.

두 인터페이스는 같은 백엔드와 데이터를 공유하므로, 어느 쪽에서 등록한 일정이든 즉시 다른 쪽에 반영된다.

### 1.2 주요 기능 요약

- 자연어 일정 등록 (Gemini 2.5 Flash)
- 일정 충돌 감지 및 시간 추천
- Discord 슬래시 명령어 및 정시·사전 알림
- 캘린더(월/주/일) 시각화 및 드래그 시간 변경
- 가용 시간(빈 시간) 히트맵 분석
- Google Calendar 양방향 연동
- Discord OAuth2 기반 웹 로그인 (JWT)
- 사용자별 데이터 격리

---

## 2. 시스템 아키텍처

### 2.1 전체 구조

```
[사용자]
   │
   ├─ Discord 클라이언트 ────────► JDA Listener
   │                                    │
   └─ 웹 브라우저 (React SPA) ──HTTPS──► Spring Boot
                                        │
                          ┌─────────────┼─────────────┐
                          ▼             ▼             ▼
                    MySQL 8 DB    Gemini 2.5    Google Calendar
                                  Flash API     API
```

Spring Boot 단일 프로세스가 다음 역할을 동시에 수행한다.

- Discord 봇 (JDA 라이브러리)
- REST API 서버
- React 정적 자원 서빙 (통합 빌드 모드)
- 외부 API 게이트웨이 (Gemini, Google Calendar)

### 2.2 기술 스택

| 영역 | 사용 기술 | 선택 근거 |
|---|---|---|
| 백엔드 언어 | Java 21 | JDA가 안정적으로 동작하는 LTS |
| 백엔드 프레임워크 | Spring Boot 4.0 | 의존성 주입, JPA, Security 통합 |
| 데이터베이스 | MySQL 8 | 일정 데이터의 관계형 모델에 적합 |
| ORM | Spring Data JPA + Hibernate | 보일러플레이트 감소 |
| Discord 클라이언트 | JDA 5.0 | 슬래시 명령어, 버튼 상호작용 지원 |
| 인증 | Spring Security OAuth2 + JJWT 0.12 | Discord OAuth2 + Stateless JWT |
| AI 분석 | Gemini 2.5 Flash REST | 한국어 자연어 처리, 비용 효율 |
| 외부 캘린더 | Google Calendar API v3 | 표준 OAuth2, 다중 사용자 지원 |
| 프론트엔드 | React 19 + Vite | 빠른 HMR, 최신 React 기능 |
| 스타일링 | Tailwind CSS v4 | 디자인 토큰 기반의 일관성 |
| 라우팅 | React Router v6 | 중첩 라우팅으로 레이아웃 공유 |
| 상태 관리 | TanStack Query | 서버 상태와 클라이언트 상태 분리 |
| 캘린더 UI | FullCalendar | 월/주/일 뷰, 드래그 지원 |

### 2.3 데이터 모델

```
Schedule (일정)
  id, task, targetTime, startTime, endTime,
  isRepeat, repeatRule, alert24h, alert1h,
  notified24h, notified1h, completedAt,
  userId (Discord ID)

ActiveTime (요일별 활동 시간)
  dayOfWeek, startHour, endHour

TargetChannel (자동 분석 대상 Discord 채널)
  channelId, channelName

TokenUsage (Gemini API 사용량 누적)
  totalPromptTokens, totalCandidateTokens, totalTokens

ButtonData (임시 — 충돌/추천 확정용)
  id, task, startTime, endTime, createdAt

GoogleToken (사용자별 OAuth2 토큰)
  userId, accessToken, refreshToken, expiresAt
```

사용자별 격리는 `Schedule.userId` 컬럼을 모든 조회·수정 API에서 `@AuthenticationPrincipal` 로 받은 ID와 비교하여 수행한다.

---

## 3. 핵심 구현

### 3.1 자연어 일정 등록

사용자가 "내일 오후 3시에 운동 1시간" 같은 문장을 입력하면 다음 흐름으로 처리된다.

```
사용자 입력
    ↓
ScheduleService.processSmartMessage()
    ↓
GeminiService.analyzeMessage()  ── 프롬프트 + 현재 시간 + 가용 시간 정보 전송
    ↓
Gemini 응답 (JSON) — 형식 분기:
    ├─ 즉시 등록      {task, time, alert24h, alert1h}
    ├─ 기간제 일정    {task, start, end}
    ├─ 시간 추천      {is_suggestion: true, suggestion_text, task, start, end}
    └─ 반복 일정      {task, is_repeat: true, repeat_rule, time}
    ↓
findConflicts() — 시간 겹침 검사
    ↓
결과별 SmartResult 반환:
    ├─ SUCCESS    — 충돌 없음, 즉시 저장
    ├─ CONFLICT   — 겹치는 일정 존재, 사용자 재확인 요청
    ├─ SUGGESTION — Gemini가 시간을 추천한 경우
    ├─ IGNORE     — 일정으로 인식 불가
    └─ ERROR      — API 오류
```

Gemini 응답이 마크다운이나 잡담을 섞을 가능성을 고려해 `extractJson()` 함수로 첫 `{` 와 마지막 `}` 사이만 추출하는 방어 로직을 두었다.

### 3.2 인증과 사용자 격리

웹 대시보드의 인증은 **Discord OAuth2 + JWT (Stateless)** 패턴을 사용한다.

```
1. 사용자가 "Discord 로 로그인" 클릭
   → /oauth2/authorization/discord 로 이동
2. Spring Security OAuth2 Client 가 Discord 인증 페이지로 리다이렉트
3. 사용자 동의 후 콜백 (/login/oauth2/code/discord) 으로 인가 코드 수신
4. Spring 이 code → access_token 교환 후 Discord /users/@me 호출
5. OAuth2SuccessHandler 가 Discord userId 를 subject 로 JWT 발급
6. 프론트엔드 콜백 URL 로 토큰을 쿼리 파라미터로 전달
7. 프론트는 localStorage 에 저장, 이후 모든 요청에 Bearer 헤더로 첨부
8. JwtAuthenticationFilter 가 매 요청마다 검증 후 SecurityContext 설정
```

API 컨트롤러는 `@AuthenticationPrincipal String userId` 로 현재 사용자 ID를 받아 본인 데이터만 반환한다. 다른 사용자의 일정을 조회하려 하면 `findById().filter(s -> userId.equals(s.getUserId()))` 패턴으로 404 처리되어 존재 자체가 노출되지 않는다.

### 3.3 충돌 감지 알고리즘

새 일정 등록 시 기존 일정과의 시간 겹침을 다음과 같이 판단한다.

```
주어진 새 일정: [newStart, newEnd]
검색 윈도우 [newStart-1h, newEnd+1h] 안의 일정들을 조회 (DB 쿼리 1회)
각 후보 일정 s 에 대해:
  sEnd = s.endTime ?? s.targetTime + 1h
  if (s.targetTime < newEnd && newStart < sEnd):
    충돌로 판정
```

이는 두 구간이 겹치는 표준적인 알고리즘이며, 인덱스를 활용한 `findAllByTargetTimeBetween` 으로 데이터베이스 부담을 최소화했다.

### 3.4 빈 시간 분석 (가용 시간)

요일별 활동 시간(예: 평일 09:00–23:00) 기준으로 30분 단위 슬롯 배열(길이 48)을 만들고, 일정이 차지하는 슬롯을 1로 마킹한다.

```
slots = [0] * 48                       # 30분 × 48 = 24시간
for each schedule s in this_week:
  startSlot = s.hour * 2 + (s.minute >= 30 ? 1 : 0)
  endSlot = ...
  slots[startSlot:endSlot] = 1

연속된 0 구간이 가용 시간이며,
그중 활동 시간대([startHour, endHour]) 안에 들어가는 부분만 추출
```

웹에서는 이 데이터를 요일×시간 히트맵으로 시각화하고, Discord 봇은 텍스트 임베드로 표시한다. 비어 있는 슬롯 정보는 Gemini 호출 시 프롬프트에 함께 전달되어 시간 추천의 근거로 활용된다.

### 3.5 정시 알림과 완료 처리

Spring `@Scheduled(cron = "0 * * * * *")` 로 매분 다음 동작을 수행한다.

```
upcoming = findAllByIsRepeatFalseAndTargetTimeBetween(now, now + 1day)
for s in upcoming:
  if s.targetTime == now:
    sendNotificationWithCompleteButton(s, "정시 알림")
  if s.alert1h and !s.notified1h and s.targetTime - 1h == now:
    sendNotification(s, "1시간 전 알림")
    s.notified1h = true
  if s.alert24h and !s.notified24h and s.targetTime - 1day == now:
    sendNotification(s, "24시간 전 알림")
    s.notified24h = true
```

정시 알림에는 `Button.success("complete:<id>", "완료")` 버튼이 함께 전송된다. 사용자가 클릭하면 `DiscordBotListener.onButtonInteraction` 이 호출되어 일정을 `completedAt = now()` 처리 후 삭제한다. 완료 버튼을 누르지 않은 일정도 다음 날 새벽 3시의 `cleanupExpiredSchedules()` 에 의해 자동 정리된다.

### 3.6 통합 빌드

개발 모드에서는 Spring Boot(8080)와 Vite dev 서버(5173)를 분리하여 HMR을 활용한다. 배포 환경에서는 React 빌드 결과를 Spring Boot의 정적 리소스로 포함하는 통합 빌드 방식을 사용한다.

```gradle
tasks.register('buildWeb', Exec) {
    workingDir = file("${project.projectDir}/../AkaideWeb")
    commandLine 'npm', 'run', 'build'   // Windows 에서는 cmd /c 로 래핑
}

tasks.register('copyWeb', Copy) {
    dependsOn 'buildWeb'
    from "${webDir}/dist"
    into staticDir
    doFirst { delete fileTree(staticDir) { exclude '.gitkeep' } }
}

tasks.named('processResources') { dependsOn 'copyWeb' }
```

이 구성으로 `./gradlew bootRun` 단일 명령으로 모든 빌드와 실행이 수행되며, 결과적으로 JAR 하나만 배포하면 서비스가 동작한다. React Router 경로(`/calendar` 등)는 `SpaController` 가 모두 `index.html` 로 forward 시켜 클라이언트 사이드 라우팅이 정상 동작하도록 보장한다.

---

## 4. 사용자 경험(UX) 결정

### 4.1 디자인 톤

초기에는 Discord 정체성에 맞춰 보라색 포인트와 다수의 이모지를 사용했으나, 이 톤은 "AI가 만든 듯한" 인상이 강하다는 피드백을 받아 **무채색 중심 + 절제된 강조**로 전면 재설계하였다. 참고한 톤은 토스(Toss), Linear 등의 한국 SaaS 디자인이다.

- 포인트 색: `#18181b` (거의 검정)
- 회색 단계: 5단계로 세분화 (`text`, `subtle`, `muted`, `border`, `line`)
- 폰트: Pretendard Variable (한글) + 시스템 폰트 fallback
- 아이콘: 의미 없는 이모지 제거, 텍스트 위주
- 강조: 색 대신 폰트 굵기와 라인 사용

### 4.2 페이지별 너비

모든 페이지를 동일한 너비로 강제하면 콘텐츠 특성에 따라 답답하거나 휑한 영역이 생긴다. 따라서 페이지마다 의도적으로 다른 최대 너비를 부여했다.

| 페이지 | 최대 너비 | 사유 |
|---|---|---|
| 홈 | 1024px | 리스트 중심, 가독성 |
| 캘린더 | 1280px | 와이드한 시각화 필요 |
| 빈 시간 | 1280px | 히트맵 가독성 |
| 설정 | 768px | 폼 중심, 좁게 |

### 4.3 결과 처리의 명확성

자연어 등록의 5가지 결과(SUCCESS, CONFLICT, SUGGESTION, IGNORE, ERROR)는 각각 다른 UI로 표현된다.

- **SUCCESS, IGNORE, ERROR** → 우측 하단 토스트 (`react-hot-toast`)
- **CONFLICT** → 중앙 모달 (사용자가 의식적으로 결정해야 함)
- **SUGGESTION** → 인라인 카드 (입력 폼 바로 아래, 검토 후 확정)

이 분기는 사용자에게 "지금 즉시 처리되었는가, 결정이 필요한가, 실패인가"를 시각적으로 명확히 구분한다.

---

## 5. 진행 상황과 향후 계획

### 5.1 완료된 단계

| Phase | 내용 | 상태 |
|---|---|---|
| 1 | REST API 골격 (9개 엔드포인트) | 완료 |
| 2 | Discord OAuth2 + JWT 인증 | 완료 |
| 3 | React 셋업 및 로그인 흐름 | 완료 |
| 3+ | 웹에서 자연어/폼 일정 등록 | 완료 |
| 4-1 | Tailwind CSS 도입 | 완료 |
| 4-2 | 공통 레이아웃(사이드바) | 완료 |
| 4-3 | 디자인 톤 재설계 | 완료 |
| 4-4 | 통계 카드, 토스트 알림 | 완료 |
| 4-5 | 캘린더(FullCalendar) | 완료 |
| 4-6 | 빈 시간 히트맵 | 완료 |
| 4-7 | 설정 페이지 | 완료 |
| 5 | 통합 빌드 (단일 서버) | 완료 |

### 5.2 남은 작업

| 항목 | 내용 |
|---|---|
| 모바일 반응형 | 사이드바를 하단 탭바로 전환 |
| 다크 모드 | CSS 변수 기반 토글 |
| 에러 처리 강화 | 백엔드 다운 시 친절한 안내 화면 |
| 실제 배포 | AWS / Oracle Cloud Free Tier |
| 테스트 자동화 | 핵심 비즈니스 로직 단위 테스트 |
| 보안 강화 | localStorage → HttpOnly 쿠키, refresh token 도입 |

### 5.3 알려진 한계

1. **JWT 만료 시간 7일** — 개발 편의를 위함이며, 운영 시에는 access token (1시간) + refresh token (7일) 패턴으로 분리 필요.
2. **`ActiveTime` 의 전역 적용** — 현재 모든 사용자가 같은 활동 시간을 공유한다. 멀티 유저 본격 도입 시 `userId` 컬럼 추가가 필요하다.
3. **Gemini API 지연** — 자연어 분석에 평균 1–3초가 소요되며, 외부 API 의존성을 가진다. 캐싱 또는 로컬 모델 도입을 검토할 수 있다.
4. **자정을 넘는 일정** — 빈 시간 분석에서는 시작 요일에만 표시하는 단순화를 적용했다.

---

## 6. 학습 성과

본 프로젝트를 통해 다음 영역에서 실질적인 경험을 축적하였다.

- **OAuth2 인증 플로우 전반의 이해** — 인가 코드 교환, JWT 발급, 클라이언트 측 토큰 관리
- **Spring Security의 SecurityFilterChain 구성** — 인증/인가 분리, 엔트리 포인트 분기
- **JPA를 통한 도메인 모델링** — 엔티티 관계 설계, 트랜잭션 경계 결정
- **외부 REST API 통합** — Gemini와 Google Calendar의 응답 형식 차이를 추상화하는 서비스 레이어 설계
- **React의 서버 상태 관리** — TanStack Query 의 `useQuery`/`useMutation` 과 캐시 무효화 패턴
- **풀스택 빌드 통합** — Gradle 태스크와 npm 빌드의 연결, SPA fallback 라우팅
- **디자인 시스템의 토큰화** — Tailwind v4 `@theme` 디렉티브를 활용한 색상·폰트 통합 관리
- **사용자 인터페이스의 미세 조정** — 너비, 여백, 폰트 크기가 인상에 미치는 영향

---

## 7. 결론

Akaide는 자연어 인터페이스와 시각적 대시보드를 결합하여 일정 관리의 마찰을 줄이는 것을 목표로 시작되었다. 5월 18일 현재 핵심 기능(자연어 등록, 충돌 감지, 캘린더 시각화, 빈 시간 분석, Discord/웹 양면 인터페이스)이 모두 동작하며, 통합 빌드를 통해 단일 JAR로 배포 가능한 상태에 도달하였다.

남은 작업은 주로 폴리싱(모바일 반응형, 다크 모드)과 운영 환경 대응(보안 강화, 자동 테스트, 클라우드 배포)이며, 현재의 아키텍처 위에서 점진적으로 추가 가능한 형태로 설계되어 있다.

---

## 부록 A. 디렉터리 구조

```
Akaide/
├── README.md
├── .gitignore
├── bot/
│   ├── build.gradle
│   └── src/main/
│       ├── java/com/akaide/bot/
│       │   ├── controller/      9 개 컨트롤러
│       │   ├── service/         5 개 서비스
│       │   ├── listener/        Discord 이벤트
│       │   ├── security/        4 개 보안 컴포넌트
│       │   ├── repository/      JPA 리포지토리
│       │   ├── domain/          엔티티
│       │   ├── dto/             10여 개 DTO
│       │   └── config/          BotConfig, WebConfig
│       └── resources/
│           ├── application.yml
│           ├── application-local.yml.example
│           └── static/          (통합 빌드 결과)
└── AkaideWeb/
    ├── package.json
    ├── vite.config.js
    ├── index.html
    └── src/
        ├── main.jsx
        ├── App.jsx
        ├── index.css
        ├── pages/               6 개 페이지
        ├── components/          7 개 재사용 컴포넌트
        ├── api/                 6 개 API 모듈
        └── styles/              fullcalendar overrides
```

## 부록 B. REST API 목록

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/auth/me` | 현재 사용자 정보 |
| GET | `/api/schedules` | 본인 전체 일정 |
| GET | `/api/schedules/today` | 오늘 일정 |
| GET | `/api/schedules/date/{date}` | 특정 날짜 일정 |
| GET | `/api/schedules/range` | 범위 조회 (캘린더용) |
| POST | `/api/schedules` | 폼 등록 |
| POST | `/api/schedules/smart` | 자연어 등록 |
| POST | `/api/schedules/confirm/{id}` | 충돌/추천 확정 |
| PATCH | `/api/schedules/{id}` | 부분 수정 |
| POST | `/api/schedules/{id}/complete` | 완료 처리 |
| DELETE | `/api/schedules/{id}` | 삭제 |
| GET | `/api/dashboard/stats` | 통계 |
| GET | `/api/free-time` | 빈 시간 히트맵 |
| GET | `/api/active-time` | 활동 시간 전체 |
| PUT | `/api/active-time/{day}` | 활동 시간 수정 |
| GET | `/api/token-usage` | Gemini 사용량 |

## 부록 C. Discord 슬래시 명령어

| 명령 | 설명 |
|---|---|
| `/일정목록` | 전체 일정 표시 |
| `/오늘일정` | 오늘 일정 |
| `/내일일정` | 내일 일정 |
| `/일정삭제 키워드` | 키워드로 삭제 |
| `/일정수정 키워드 변경` | 자연어 수정 |
| `/빈시간확인` | 가용 시간 분석 |
| `/활동설정 요일 시작 종료` | 활동 시간 설정 |
| `/구글연동` | Google Calendar 연동 |
| `/채널등록` | 자동 분석 채널 등록 |
| `/채널삭제` | 자동 분석 해제 |
| `/토큰확인` | API 사용량 |
