package com.leyon.lumin.ai.provider;

/** 一次文本生成请求；system 可为空。 */
public record AiRequest(String systemPrompt, String userPrompt, double temperature, int maxTokens) {
}
