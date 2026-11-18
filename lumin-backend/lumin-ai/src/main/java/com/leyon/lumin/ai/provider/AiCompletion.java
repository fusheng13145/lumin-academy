package com.leyon.lumin.ai.provider;

/** 统一供应商返回；token 统计用于后续预算与成本看板。 */
public record AiCompletion(
        String content,
        String provider,
        String model,
        int promptTokens,
        int completionTokens,
        String finishReason
) {
    public String usageSummary() {
        return provider + "/" + model + " prompt=" + promptTokens + " completion=" + completionTokens;
    }
}
