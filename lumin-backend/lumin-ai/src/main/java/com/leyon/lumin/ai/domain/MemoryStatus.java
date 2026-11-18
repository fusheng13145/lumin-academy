package com.leyon.lumin.ai.domain;

/** 记忆事实状态；AI 抽取结果先进入 CANDIDATE，作者确认后才 ACTIVE。 */
public enum MemoryStatus {
    CANDIDATE,
    ACTIVE,
    ARCHIVED,
    REJECTED
}
