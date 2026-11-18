package com.leyon.lumin.moderation.service;

import com.leyon.lumin.moderation.domain.QualitySampler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 默认抽检闸：HMAC(policySeed, caseId) 5% 桶（03 §5.3）。
 * policySeed 由配置注入（生产经 Secret Manager，04 §5.3 口径——**登记**：
 * 当前为配置项占位，Secret Manager 接入后替换来源）。
 */
@Component
public class DefaultAutoSampleGate implements AutoSampleGate {

    private final byte[] policySeed;

    public DefaultAutoSampleGate(
            @Value("${lumin.review.sample-seed:lumin-review-seed-dev}") String seed) {
        this.policySeed = seed.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public boolean sampled(String caseId) {
        return QualitySampler.autoDecisionSampled(policySeed, UUID.fromString(caseId));
    }
}
