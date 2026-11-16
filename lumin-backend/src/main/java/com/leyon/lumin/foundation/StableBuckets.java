package com.leyon.lumin.foundation;

import java.nio.ByteBuffer;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 万分比稳定分桶（03 §2.4 / §5.3 共用）：HMAC-SHA256(seed, 主体规范字节) 前 8 字节
 * 无符号映射到 [0, 10000)。同 seed + 主体恒定（稳定采样 / 灰度分桶的前提），
 * 客户端无法通过挑选 ID 逃避。
 *
 * <p>fail-closed 约定：seed 缺失 / 无效时返回 -1，由调用方按各自的安全默认处理
 * （审核抽检 → 全量复核；配置灰度 → 全量基线），绝不静默放行。
 * policySeed 由 Secret Manager 提供（03 §5.3、04 §5.1），不得使用客户端可预测种子。
 */
public final class StableBuckets {

    /** 桶总数：万分比。 */
    public static final int TOTAL_BUCKETS = 10000;

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private StableBuckets() {
    }

    /**
     * 采样桶 [0, 10000)；seed 为 null / 空或主体为 null 时返回 -1（fail-closed）。
     *
     * @param policySeed 策略种子字节（Secret Manager 注入）
     * @param subjectId  主体 ID（案件 ID / 用户 ID，规范 16 字节参与 HMAC）
     */
    public static int bucket(byte[] policySeed, UUID subjectId) {
        if (policySeed == null || policySeed.length == 0 || subjectId == null) {
            return -1;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(policySeed, HMAC_ALGORITHM));
            byte[] macBytes = mac.doFinal(canonicalBytes(subjectId));
            long value = ByteBuffer.wrap(macBytes, 0, 8).getLong();
            return (int) Long.remainderUnsigned(value, TOTAL_BUCKETS);
        } catch (Exception e) {
            // HmacSHA256 为 JDK 必备算法，此分支仅防御性兜底；按 fail-closed 处理
            return -1;
        }
    }

    /** UUID 规范 16 字节（RFC 4122 big-endian），保证同 ID 恒定 HMAC 输入。 */
    private static byte[] canonicalBytes(UUID subjectId) {
        return ByteBuffer.allocate(16)
                .putLong(subjectId.getMostSignificantBits())
                .putLong(subjectId.getLeastSignificantBits())
                .array();
    }
}
