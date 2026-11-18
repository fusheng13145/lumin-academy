package com.leyon.lumin.ai.domain;

/** 创作模式：传统写作不调用 AI；AI 辅助与纯 AI 使用不同的编排强度。 */
public enum AiMode {
    TRADITIONAL,
    ASSISTED,
    AUTONOMOUS;

    public boolean usesAi() {
        return this != TRADITIONAL;
    }
}
