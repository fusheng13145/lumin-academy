package com.leyon.lumin.ai.api;

/** POST /api/v1/ai/runs 请求；mode 必须是 ASSISTED 或 AUTONOMOUS。 */
public record AiRunRequest(String workId, String chapterId, String mode, String instruction) {
}
