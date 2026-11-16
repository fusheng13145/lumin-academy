package com.leyon.lumin.moderation.domain;

import com.leyon.lumin.foundation.StableBuckets;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 质检稳定采样（03 §5.3：5% 自动抽检、20% MEDIUM 质检；T48 机制前提：
 * 重复运行稳定采样一致，客户端无法挑选 caseId 逃避）。
 */
class QualitySamplerTest {

    private static final byte[] SEED = "lumin-policy-seed-2026".getBytes();
    private static final UUID CASE_ID =
            UUID.fromString("2a7c9e1b-4d3f-4c2a-8b5e-6f7a8b9c0d1e");

    private static UUID caseId(long index) {
        return new UUID(0x2A7C9E1B4D3F4C2AL, 0x8B5E6F7A8B9C0D1EL + index);
    }

    @Test
    void samplingIsDeterministicAndStable() {
        // 同 seed + caseId 恒定（T48：重复运行稳定采样一致）
        assertEquals(QualitySampler.bucket(SEED, CASE_ID),
                QualitySampler.bucket(SEED, CASE_ID));
        assertEquals(QualitySampler.autoDecisionSampled(SEED, CASE_ID),
                QualitySampler.autoDecisionSampled(SEED, CASE_ID));
        assertEquals(QualitySampler.mediumQcRequired(SEED, CASE_ID),
                QualitySampler.mediumQcRequired(SEED, CASE_ID));
        // 与 seed 无关的恒定性：两次构造相同输入（本地固定种子）结果一致
        byte[] sameSeed = "lumin-policy-seed-2026".getBytes();
        assertEquals(QualitySampler.bucket(SEED, CASE_ID),
                QualitySampler.bucket(sameSeed, CASE_ID));
    }

    @Test
    void bucketStaysInRangeAndSpreads() {
        Set<Integer> buckets = new HashSet<>();
        for (long i = 0; i < 500; i++) {
            int bucket = QualitySampler.bucket(SEED, caseId(i));
            assertTrue(bucket >= 0 && bucket < StableBuckets.TOTAL_BUCKETS,
                    "bucket 越界: " + bucket);
            buckets.add(bucket);
        }
        // 500 个样本至少覆盖 10 个不同桶：排除退化为常量函数的实现
        assertTrue(buckets.size() >= 10, "采样桶未分散: " + buckets.size());
    }

    @Test
    void thresholdsMatchBucketSemantics() {
        for (long i = 0; i < 200; i++) {
            UUID id = caseId(i);
            int bucket = QualitySampler.bucket(SEED, id);
            assertEquals(bucket < QualitySampler.AUTO_DECISION_SAMPLE_BUCKETS,
                    QualitySampler.autoDecisionSampled(SEED, id));
            assertEquals(bucket < QualitySampler.MEDIUM_QC_SAMPLE_BUCKETS,
                    QualitySampler.mediumQcRequired(SEED, id));
        }
    }

    @Test
    void missingSeedFailsClosedToHumanReview() {
        // fail-closed：seed 缺失 → 全量按命中抽检处理，不静默放行
        assertEquals(-1, QualitySampler.bucket(null, CASE_ID));
        assertEquals(-1, QualitySampler.bucket(new byte[0], CASE_ID));
        assertEquals(-1, QualitySampler.bucket(SEED, null));
        assertTrue(QualitySampler.autoDecisionSampled(null, CASE_ID));
        assertTrue(QualitySampler.mediumQcRequired(new byte[0], CASE_ID));
        assertTrue(QualitySampler.autoDecisionSampled(SEED, null));
    }

    @Test
    void differentSeedsChangeBuckets() {
        byte[] otherSeed = "another-policy-seed".getBytes();
        boolean differs = false;
        for (long i = 0; i < 20; i++) {
            if (QualitySampler.bucket(SEED, caseId(i))
                    != QualitySampler.bucket(otherSeed, caseId(i))) {
                differs = true;
                break;
            }
        }
        assertTrue(differs, "不同种子未改变采样桶");
    }
}
