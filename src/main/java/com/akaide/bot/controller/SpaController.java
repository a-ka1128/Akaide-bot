package com.akaide.bot.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Single Page Application(SPA) fallback.
 *
 * React Router 경로 (예: /calendar, /settings) 로 직접 접근하거나
 * 새로고침하면 Spring이 그 경로의 컨트롤러를 찾으려 함 → 없으니 404.
 *
 * 이 컨트롤러가 "API/정적 자원이 아닌 모든 경로"를 index.html 로 forward 시켜
 * React Router가 클라이언트 사이드에서 라우팅을 처리하도록 한다.
 *
 * 매칭 패턴 설명:
 *   {path:[^\.]*}        — 점(.) 없는 단일 세그먼트 (정적 파일 .js, .css 제외)
 *   {path:^(?!api).*$}   — 'api' 로 시작하지 않는 경로 (옵션 — 여기선 단순화)
 *
 * 결과적으로 / , /calendar, /settings 같은 React 라우트는 모두 index.html 로 매핑.
 */
@Controller
public class SpaController {

    @GetMapping(value = {
            "/",
            "/calendar",
            "/school",
            "/free-time",
            "/settings",
            "/login",
            "/oauth/callback"
    })
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
