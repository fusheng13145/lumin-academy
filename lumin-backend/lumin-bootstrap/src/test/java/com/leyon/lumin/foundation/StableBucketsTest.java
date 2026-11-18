package com.leyon.lumin.foundation;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 万分比稳定分桶（03 §2.4 / §5.3 共用）。
 * 独立验证：测试内自建 HMAC 映射路径，与实现比对（验证「前 8 字节无符号 mod 10000」规则）。
 */
class StableBucketsTest {

    private static final byte[] SEED = "lumin-policy-seed-2026".getBytes();
    private static final UUID SUBJECT =
            UUID.fromString("2a7c9e1b-4d3f-4c2a-8b5e-6f7a8b9c0d1e");

    private static UUID subject(long index) {
        return new UUID(0x2A7C9E1B4D3F4C2AL, 0x8B5E6F7A8B9C0D1EL + index);
    }

    /** 独立重建映射规则：HMAC → 前 8 字节 big-endian → 无符号 mod 10000。 */
    private static int independentBucket(byte[] seed, UUID subject) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(seed, "HmacSHA256"));
        ByteBuffer buffer = ByteBuffer.allocate(16)
                .putLong(subject.getMostSignificantBits())
                .putLong(subject.getLeastSignificantBits());
        byte[] macBytes = mac.doFinal(buffer.array());
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (macBytes[i] & 0xFFL);
        }
        // 无符号取模
        return (int) Long.remainderUnsigned(value, 10000);
    }

    @Test
    void bucketMatchesIndependentHmacMapping() throws Exception {
        for (long i = 0; i < 100; i++) {
            UUID subject = subject(i);
            assertEquals(independentBucket(SEED, subject),
                    StableBuckets.bucket(SEED, subject), "subject#" + i);
        }
    }

    @Test
    void determinismAndRangeAndSpread() {
        assertEquals(StableBuckets.bucket(SEED, SUBJECT), StableBuckets.bucket(SEED, SUBJECT));
        Set<Integer> buckets = new HashSet<>();
        for (long i = 0; i < 500; i++) {
            int bucket = StableBuckets.bucket(SEED, subject(i));
            assertTrue(bucket >= 0 && bucket < StableBuckets.TOTAL_BUCKETS);
            buckets.add(bucket);
        }
        assertTrue(buckets.size() >= 10, "采样桶未分散: " + buckets.size());
    }

    @Test
    void missingSeedFailsClosed() {
        assertEquals(-1, StableBuckets.bucket(null, SUBJECT));
        assertEquals(-1, StableBuckets.bucket(new byte[0], SUBJECT));
        assertEquals(-1, StableBuckets.bucket(SEED, null));
        // 不同种子改变分桶
        boolean differs = false;
        for (long i = 0; i < 20; i++) {
            if (StableBuckets.bucket(SEED, subject(i))
                    != StableBuckets.bucket("another-seed".getBytes(), subject(i))) {
                differs = true;
                break;
            }
        }
        assertTrue(differs, "不同种子未改变采样桶");
    }
}
