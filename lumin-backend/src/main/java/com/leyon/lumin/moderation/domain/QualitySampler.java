package com.leyon.lumin.moderation.domain;

import com.leyon.lumin.foundation.StableBuckets;

import java.util.UUID;

/**
 * 质检稳定采样（03 §5.3 / 04 §5.3）：自动决定抽检 5%、MEDIUM 人工单审 20% 质检。
 *
 * <p>分桶算法统一委托 {@link StableBuckets}（HMAC-SHA256(policySeed, caseId) 万分比）：
 * 同 seed + caseId 结果恒定（稳定采样，T48 机制前提），客户端无法通过挑选 caseId
 * 逃避抽检；policySeed 由 Secret Manager 提供（04 §5.3），不得使用客户端可预测种子。
 *
 * <p>fail-closed：seed 缺失 / 无效时按「命中抽检」处理（自动决定全量转人工复核），
 * 绝不静默放行。
 */
public final class QualitySampler {

    /** 自动决定抽检 5%（万分比 500 桶）。 */
    public static final int AUTO_DECISION_SAMPLE_BUCKETS = 500;
    /** MEDIUM 人工单审质检 20%（万分比 2000 桶）。 */
    public static final int MEDIUM_QC_SAMPLE_BUCKETS = 2000;

    private QualitySampler() {
    }

    /**
     * 采样桶 [0, 10000)；seed 为 null / 空时返回 -1（fail-closed 语义）。
     *
     * @param policySeed 策略种子字节（Secret Manager 注入，非口令明文）
     * @param caseId     案件 ID（UUIDv4，规范 16 字节参与 HMAC）
     */
    public static int bucket(byte[] policySeed, UUID caseId) {
        return StableBuckets.bucket(policySeed, caseId);
    }

    /** 自动决定是否命中 5% 抽检；seed 无效时恒为 true（全量复核）。 */
    public static boolean autoDecisionSampled(byte[] policySeed, UUID caseId) {
        int bucket = bucket(policySeed, caseId);
        return bucket < 0 || bucket < AUTO_DECISION_SAMPLE_BUCKETS;
    }

    /** MEDIUM 案件是否命中 20% 质检；seed 无效时恒为 true。 */
    public static boolean mediumQcRequired(byte[] policySeed, UUID caseId) {
        int bucket = bucket(policySeed, caseId);
        return bucket < 0 || bucket < MEDIUM_QC_SAMPLE_BUCKETS;
    }
}
