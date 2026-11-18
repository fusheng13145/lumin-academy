package com.leyon.lumin.configuration.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.StableBuckets;
import com.leyon.lumin.foundation.values.ConfigKey;
import com.leyon.lumin.configuration.domain.GrayRolloutPolicy.Slots;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 配置灰度稳定分桶与激活校验（03 §2.4 / 05 §4.2；T30–T32 决策面）。 */
class GrayRolloutPolicyTest {

    private static final byte[] SEED = "lumin-config-rollout-seed".getBytes();
    private static final UUID SUBJECT =
            UUID.fromString("3f2a1b4c-5d6e-4f70-8a90-1b2c3d4e5f60");
    private static final Long FULL = 100L;
    private static final Long GRAY = 101L;

    @Test
    void accessAndCatalogKeysAllowOnlyFullRollout() {
        // 05 §4.2：ACCESS_POLICY 同 CONTENT_CATALOG 固定 10000，不能部分灰度
        for (ConfigKey key : List.of(ConfigKey.ACCESS_POLICY, ConfigKey.CONTENT_CATALOG)) {
            assertEquals(ErrorCode.OK,
                    GrayRolloutPolicy.validateActivation(key, 10000, false), key.name());
            assertEquals(ErrorCode.P1001,
                    GrayRolloutPolicy.validateActivation(key, 9999, true), key.name());
            assertEquals(ErrorCode.P1001,
                    GrayRolloutPolicy.validateActivation(key, 1, true), key.name());
        }
    }

    @Test
    void zeroRolloutNeverActivates() {
        // 03 §2.4：0 比例不得激活
        for (ConfigKey key : List.of(ConfigKey.values())) {
            assertEquals(ErrorCode.P1001,
                    GrayRolloutPolicy.validateActivation(key, 0, true), key.name());
        }
    }

    @Test
    void grayRequiresFullBaselineAndValidRange() {
        assertEquals(ErrorCode.OK, GrayRolloutPolicy.validateActivation(
                ConfigKey.REVIEW_ROUTING, 9999, true));
        assertEquals(ErrorCode.P1001, GrayRolloutPolicy.validateActivation(
                ConfigKey.REVIEW_ROUTING, 9999, false));
        assertEquals(ErrorCode.P1001, GrayRolloutPolicy.validateActivation(
                ConfigKey.REVIEW_ROUTING, 10001, true));
        assertEquals(ErrorCode.P1001, GrayRolloutPolicy.validateActivation(
                ConfigKey.REVIEW_ROUTING, null, true));
        assertEquals(ErrorCode.P1001, GrayRolloutPolicy.validateActivation(
                null, 9999, true));
        // 10000 为全量槽语义：无需既有基线
        assertEquals(ErrorCode.OK, GrayRolloutPolicy.validateActivation(
                ConfigKey.REVIEW_ROUTING, 10000, false));
    }

    @Test
    void selectionBindsExactlyOneSlotByBucket() {
        int bucket = StableBuckets.bucket(SEED, SUBJECT);
        assertTrue(bucket >= 0 && bucket < StableBuckets.TOTAL_BUCKETS);
        // 命中：grayBasisPoints = bucket + 1 → 主体桶 < 阈值 → 灰度版本
        assertEquals(GRAY, GrayRolloutPolicy.select(SEED, SUBJECT,
                new Slots(FULL, GRAY, bucket + 1)));
        // 未命中：grayBasisPoints = bucket → 主体桶 ≥ 阈值 → 全量基线
        assertEquals(FULL, GrayRolloutPolicy.select(SEED, SUBJECT,
                new Slots(FULL, GRAY, bucket)));
        // 每次决策只绑定一个版本：结果非灰即全，不拼接
        Long selected = GrayRolloutPolicy.select(SEED, SUBJECT,
                new Slots(FULL, GRAY, 9999));
        assertTrue(selected.equals(FULL) || selected.equals(GRAY));
    }

    @Test
    void selectionIsDeterministicAndFailsClosedToBaseline() {
        Slots slots = new Slots(FULL, GRAY, 9999);
        assertEquals(GrayRolloutPolicy.select(SEED, SUBJECT, slots),
                GrayRolloutPolicy.select(SEED, SUBJECT, slots));
        // fail-closed：seed 缺失 → 全量基线（不得把主体暴露给未验证灰度槽）
        assertEquals(FULL, GrayRolloutPolicy.select(null, SUBJECT, slots));
        assertEquals(FULL, GrayRolloutPolicy.select(new byte[0], SUBJECT, slots));
        // 无灰度 / 非法灰度 → 全量
        assertEquals(FULL, GrayRolloutPolicy.select(SEED, SUBJECT,
                new Slots(FULL, null, null)));
        assertEquals(FULL, GrayRolloutPolicy.select(SEED, SUBJECT,
                new Slots(FULL, GRAY, 0)));
        // 无全量基线：选择返回 null（服务层不得拿 null 参与决策）
        assertNull(GrayRolloutPolicy.select(SEED, SUBJECT, new Slots(null, GRAY, 500)));
        assertNull(GrayRolloutPolicy.select(SEED, SUBJECT, null));
    }
}
