package com.leyon.lumin.moderation.service;

import com.leyon.lumin.foundation.StableBuckets;
import com.leyon.lumin.foundation.port.ReviewRoutingConfigPort;
import com.leyon.lumin.moderation.domain.QualitySampler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 默认抽检闸（UGC-008 配置驱动）：抽检比例取 REVIEW_ROUTING ACTIVE 修订解析值
 * （resolveForCase 按案件灰度分桶；无基线回退 500bp=5% 安全默认）。
 * bucket = HMAC(policySeed, caseId) ∈ [0,10000)；比例内命中转人工；
 * seed 缺失 bucket=-1 fail-closed（全量复核）。policySeed 生产经 Secret Manager。
 */
@Component
public class DefaultAutoSampleGate implements AutoSampleGate {

    private final byte[] policySeed;
    private final ReviewRoutingConfigPort routingConfigPort;

    public DefaultAutoSampleGate(
            @Value("${lumin.review.sample-seed:lumin-review-seed-dev}") String seed,
            ReviewRoutingConfigPort routingConfigPort) {
        this.policySeed = seed.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        this.routingConfigPort = routingConfigPort;
    }

    @Override
    public boolean sampled(String caseId) {
        UUID caseUuid;
        try {
            caseUuid = UUID.fromString(caseId);
        } catch (IllegalArgumentException e) {
            return true; // 非法案件标识 fail-closed：全量复核
        }
        int bucket = StableBuckets.bucket(policySeed, caseUuid);
        if (bucket < 0) {
            return true; // fail-closed：全量复核
        }
        int rateBp = routingConfigPort.resolveForCase(caseUuid).sampleRateBp();
        return bucket < rateBp;
    }
}
