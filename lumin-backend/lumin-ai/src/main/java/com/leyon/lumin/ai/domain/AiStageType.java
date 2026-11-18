package com.leyon.lumin.ai.domain;

/** workflow 阶段；每次尝试单独落一行，便于前端展示“当前思考到哪一步”。 */
public enum AiStageType {
    MEMORY_CONTEXT,
    PLAN,
    DRAFT,
    CONSISTENCY,
    REVISION,
    MEMORY_EXTRACT,
    CANDIDATE
}
