package com.leyon.lumin.configuration.service;

import com.leyon.lumin.configuration.repository.ConfigRevisionMapper;
import com.leyon.lumin.configuration.repository.ConfigRevisionRow;
import com.leyon.lumin.foundation.StableBuckets;
import com.leyon.lumin.foundation.port.ReviewRoutingConfigPort;
import com.leyon.lumin.foundation.values.ConfigKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

/**
 * REVIEW_ROUTING 配置解析适配器（UGC-008：配置到业务消费者闭环）：
 * 全量基线 + 至多一条灰度修订按案件 ID 稳定分桶（StableBuckets，与抽检同源
 * 不可规避）；无 ACTIVE 基线时回退安全默认（9800bp / 500bp / 未成年保护开）。
 * payload 字段缺失回退既有值（提案校验已保证不低于基线——T31）。
 */
@Service
public class ReviewRoutingConfigAdapter implements ReviewRoutingConfigPort {

    private final ConfigRevisionMapper mapper;
    private final byte[] graySeed;
    private final tools.jackson.databind.ObjectMapper objectMapper;

    public ReviewRoutingConfigAdapter(ConfigRevisionMapper mapper,
                                      @Value("${lumin.review.sample-seed:lumin-review-seed-dev}")
                                      String graySeed,
                                      tools.jackson.databind.ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.graySeed = graySeed.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewRoutingConfig resolveForCase(UUID caseId) {
        ConfigRevisionRow full = mapper.findActiveFull(ConfigKey.REVIEW_ROUTING.name());
        ConfigRevisionRow gray = mapper.findActiveGray(ConfigKey.REVIEW_ROUTING.name());
        JsonNode payload = null;
        if (gray != null && caseId != null) {
            int bucket = StableBuckets.bucket(graySeed, caseId);
            if (bucket >= 0 && bucket < gray.rolloutBasisPoints()) {
                payload = parse(gray.payload());
            }
        }
        if (payload == null) {
            payload = full == null ? null : parse(full.payload());
        }
        if (payload == null) {
            return ReviewRoutingConfig.defaults();
        }
        int confidence = intValue(payload, "minAutoConfidenceBp",
                ReviewRoutingConfig.defaults().minAutoConfidenceBp());
        int sampleRate = intValue(payload, "sampleRateBp",
                ReviewRoutingConfig.defaults().sampleRateBp());
        boolean minorProtection = boolValue(payload, "minorProtectionEnabled", true);
        return new ReviewRoutingConfig(confidence, sampleRate, minorProtection);
    }

    private JsonNode parse(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            // 修订 payload 已过提案校验；解析失败按缺配置回退默认（fail-safe 到安全默认）
            return null;
        }
    }

    private static int intValue(JsonNode node, String field, int fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isNumber() ? fallback : value.asInt();
    }

    private static boolean boolValue(JsonNode node, String field, boolean fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isBoolean() ? fallback : value.asBoolean();
    }
}
