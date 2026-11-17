package com.leyon.lumin.configuration.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import tools.jackson.databind.JsonNode;
import com.leyon.lumin.foundation.values.ConfigKey;

import java.util.Set;

/**
 * 配置 payload 类型化校验与最低合规底线（05 §3.4 createConfigRevision
 * 「类型化提案，最低合规规则不能改弱」；T31）。
 *
 * <p>REVIEW_ROUTING（审核规则）与 ACCESS_POLICY（年龄规则）为合规敏感键：
 * ① 未成年保护开关不可关闭（T31「关闭未成年保护」→ 拒绝）；② 机审自动通过
 * 置信下限不可低于既有 ACTIVE 基线（降低置信度 → 拒绝），且不低于绝对下限；
 * ③ 抽检比例不可低于既有基线（降低抽检底线 → 拒绝），且不低于绝对下限。
 * P0 契约口径：A 级默认关闭（adultContentEnabled 只能为 false）、
 * UPDATE 类通知为 P1 占位（updateDigestEnabled 只能为 false）。
 * 各键白名单外的字段一律拒绝（类型化提案，不接受自由 payload）。
 */
public final class ConfigPayloadValidator {

    public static final int MIN_AUTO_CONFIDENCE_BP = 9000;
    public static final int MIN_SAMPLE_RATE_BP = 100;
    public static final int DEFAULT_AUTO_CONFIDENCE_BP = 9900;
    public static final int DEFAULT_SAMPLE_RATE_BP = 500;

    /** 各键允许的字段白名单（类型化提案）。 */
    private static final Set<String> REVIEW_ROUTING_FIELDS = Set.of(
            "minAutoConfidenceBp", "sampleRateBp", "minorProtectionEnabled");
    private static final Set<String> ACCESS_POLICY_FIELDS = Set.of("adultContentEnabled");
    private static final Set<String> NOTIFICATION_POLICY_FIELDS = Set.of("updateDigestEnabled");
    private static final Set<String> CONTENT_CATALOG_FIELDS = Set.of("defaultPageSize");

    private ConfigPayloadValidator() {
    }

    /** 提案校验：返回 null 表示合法，否则为拒绝错误码（P1001）。 */
    public static ErrorCode validateProposal(ConfigKey key, JsonNode payload,
                                             JsonNode activeBaseline) {
        if (payload == null || !payload.isObject()) {
            return ErrorCode.P1001;
        }
        Set<String> allowed = allowedFields(key);
        for (var field : payload.properties()) {
            if (!allowed.contains(field.getKey())) {
                return ErrorCode.P1001;
            }
        }
        return switch (key) {
            case REVIEW_ROUTING -> validateReviewRouting(payload, activeBaseline);
            case ACCESS_POLICY -> validateAccessPolicy(payload);
            case NOTIFICATION_POLICY -> validateNotificationPolicy(payload);
            case CONTENT_CATALOG -> validateContentCatalog(payload);
        };
    }

    private static ErrorCode validateReviewRouting(JsonNode payload, JsonNode baseline) {
        // T31：关闭未成年保护 → 拒绝
        if (payload.has("minorProtectionEnabled")
                && !payload.get("minorProtectionEnabled").asBoolean(true)) {
            return ErrorCode.P1001;
        }
        // 降低置信度 / 降低抽检底线 → 拒绝（相对既有基线，且不低于绝对下限）
        int baselineConfidence = baseline == null ? DEFAULT_AUTO_CONFIDENCE_BP
                : intValue(baseline, "minAutoConfidenceBp", DEFAULT_AUTO_CONFIDENCE_BP);
        int baselineSample = baseline == null ? DEFAULT_SAMPLE_RATE_BP
                : intValue(baseline, "sampleRateBp", DEFAULT_SAMPLE_RATE_BP);
        int confidence = intValue(payload, "minAutoConfidenceBp", baselineConfidence);
        int sampleRate = intValue(payload, "sampleRateBp", baselineSample);
        if (confidence < Math.max(MIN_AUTO_CONFIDENCE_BP, baselineConfidence)
                || sampleRate < Math.max(MIN_SAMPLE_RATE_BP, baselineSample)) {
            return ErrorCode.P1001;
        }
        return ErrorCode.OK;
    }

    private static ErrorCode validateAccessPolicy(JsonNode payload) {
        // P0 契约：A 级默认关闭——开启成年内容开关在 P0 不可提案
        if (payload.has("adultContentEnabled")
                && payload.get("adultContentEnabled").asBoolean(false)) {
            return ErrorCode.P1001;
        }
        return ErrorCode.OK;
    }

    private static ErrorCode validateNotificationPolicy(JsonNode payload) {
        // UPDATE 类通知为 P1 占位（04 §5.1）——P0 不可启用
        if (payload.has("updateDigestEnabled")
                && payload.get("updateDigestEnabled").asBoolean(false)) {
            return ErrorCode.P1001;
        }
        return ErrorCode.OK;
    }

    private static ErrorCode validateContentCatalog(JsonNode payload) {
        if (payload.has("defaultPageSize")) {
            int size = payload.get("defaultPageSize").asInt(0);
            if (size < 1 || size > 100) {
                return ErrorCode.P1001;
            }
        }
        return ErrorCode.OK;
    }

    private static Set<String> allowedFields(ConfigKey key) {
        return switch (key) {
            case REVIEW_ROUTING -> REVIEW_ROUTING_FIELDS;
            case ACCESS_POLICY -> ACCESS_POLICY_FIELDS;
            case NOTIFICATION_POLICY -> NOTIFICATION_POLICY_FIELDS;
            case CONTENT_CATALOG -> CONTENT_CATALOG_FIELDS;
        };
    }

    private static int intValue(JsonNode node, String field, int fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.canConvertToInt() ? fallback : value.asInt();
    }
}
