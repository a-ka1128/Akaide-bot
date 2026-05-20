package com.akaide.bot.service;

import lombok.Getter;
import java.time.LocalDateTime;

@Getter
public class SmartResult {
    public enum Type { SUCCESS, SUGGESTION, IGNORE, ERROR, CONFLICT }

    private final Type type;
    private String task;
    private String text;
    private LocalDateTime start;
    private LocalDateTime end;
    private String buttonId;
    private String conflictDescription; // 충돌 일정 정보 (텍스트)

    private SmartResult(Type type) { this.type = type; }

    public static SmartResult success(String task) {
        SmartResult r = new SmartResult(Type.SUCCESS); r.task = task; return r;
    }
    public static SmartResult suggestion(String text, String task, LocalDateTime s, LocalDateTime e, String bid) {
        SmartResult r = new SmartResult(Type.SUGGESTION);
        r.text = text; r.task = task; r.start = s; r.end = e; r.buttonId = bid;
        return r;
    }
    public static SmartResult conflict(String task, LocalDateTime s, LocalDateTime e, String bid, String description) {
        SmartResult r = new SmartResult(Type.CONFLICT);
        r.task = task; r.start = s; r.end = e; r.buttonId = bid;
        r.conflictDescription = description;
        return r;
    }
    public static SmartResult ignore() { return new SmartResult(Type.IGNORE); }
    public static SmartResult error() { return new SmartResult(Type.ERROR); }
}