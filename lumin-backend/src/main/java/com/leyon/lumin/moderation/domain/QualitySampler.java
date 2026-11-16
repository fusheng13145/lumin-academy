package com.leyon.lumin.moderation.domain;

import java.nio.ByteBuffer;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 质检稳定采样（03 §5.3 / 04 §5.3）：自动决定抽检 5%、MEDIUM 人工单审 20% 质检。
 *
 * <p>采样桶 = HMAC-SHA256(policySeed, caseId 规范字节) 前 8 字节无符号映射到
 * [0, 10000)。同 seed + caseId 结果恒定（稳定采样，T48 机制前提），客户端无法
 * 通过挑选 caseId 逃避抽检；policySeed 由 Secret Manager 提供（04 §5.3），
 * 不得使用客户端可预测的种子。
 *
 * <p>fail-closed：seed 缺失 / 无效时 bucket 返回 -1，全部按「命中抽检」处理
 * （自动决定全量转人工复核），绝不静默放行。
 */
public final class QualitySampler {

    /** 桶总数：万分比（03 §5.3 的 5% / 20% 即 500 / 2000 桶）。 */
    public static final int TOTAL_BUCKETS = 10000;
    /** 自动决定抽检 5%。 */
    public static final int AUTO_DECISION_SAMPLE_BUCKETS = 500;
    /** MEDIUM 人工单审质检 20%。 */
    public static final int MEDIUM_QC_SAMPLE_BUCKETS = 2000;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private QualitySampler() {
    }

    /**
     * 采样桶 [0, 10000)；seed 为 null / 空时返回 -1（fail-closed 语义）。
     *
     * @param policySeed 策略种子字节（Secret Manager 注入，非口令明文）
     * @param caseId     案件 ID（UUIDv4，规范 16 字节参与 HMAC）
     */
    public static int bucket(byte[] policySeed, UUID caseId) {
        if (policySeed == null || policySeed.length == 0 || caseId == null) {
            return -1;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(policySeed, HMAC_ALGORITHM));
            byte[] macBytes = mac.doFinal(caseIdCanonicalBytes(caseId));
            long value = ByteBuffer.wrap(macBytes, 0, 8).getLong();
            return (int) Long.remainderUnsigned(value, TOTAL_BUCKETS);
        } catch (Exception e) {
            // HmacSHA256 为 JDK 必备算法，此分支仅防御性兜底；按 fail-closed 处理
            return -1;
        }
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

    /** UUID 规范 16 字节（RFC 4122 big-endian），保证同 ID 恒定 HMAC 输入。 */
    private static byte[] caseIdCanonicalBytes(UUID caseId) {
        return ByteBuffer.allocate(16)
                .putLong(caseId.getMostSignificantBits())
                .putLong(caseId.getLeastSignificantBits())
                .array();
    }
}
