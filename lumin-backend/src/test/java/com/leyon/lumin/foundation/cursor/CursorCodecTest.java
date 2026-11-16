package com.leyon.lumin.foundation.cursor;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.cursor.CursorCodec.DecodedCursor;
import com.leyon.lumin.foundation.cursor.CursorCodec.Payload;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 游标编解码（04 §7 / 05 §2：签名、15 分钟过期、过滤摘要绑定、limit 边界）。 */
class CursorCodecTest {

    private static final byte[] KEY = "cursor-signing-key-2026".getBytes();
    private static final Instant ISSUED = Instant.parse("2026-11-11T16:00:00Z");
    private static final UUID LAST_ID =
            UUID.fromString("b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6a");
    private static final UUID SUBJECT =
            UUID.fromString("3f2a1b4c-5d6e-4f70-8a90-1b2c3d4e5f60");
    private static final String FILTER_DIGEST =
            "0123456789abcdef".repeat(4);

    private static Payload payload() {
        return new Payload("2026-11-12T00:00:00Z", LAST_ID, FILTER_DIGEST, SUBJECT, ISSUED);
    }

    @Test
    void roundTripPreservesPayload() {
        String token = CursorCodec.encode(payload(), KEY);
        assertTrue(token.length() <= CursorCodec.MAX_TOKEN_LENGTH);
        DecodedCursor decoded = CursorCodec.decode(token, KEY,
                ISSUED.plusSeconds(60), FILTER_DIGEST);
        assertEquals(ErrorCode.OK, decoded.code());
        assertEquals(payload(), decoded.payload());
    }

    @Test
    void limitRules() {
        // 默认 20、最大 100（04 §7）；显式非法值拒绝
        assertEquals(20, CursorCodec.resolveLimit(null));
        assertEquals(55, CursorCodec.resolveLimit(55));
        assertEquals(CursorCodec.MAX_LIMIT, CursorCodec.resolveLimit(150));
        assertEquals(ErrorCode.OK, CursorCodec.validateLimit(null));
        assertEquals(ErrorCode.OK, CursorCodec.validateLimit(1));
        assertEquals(ErrorCode.OK, CursorCodec.validateLimit(100));
        assertEquals(ErrorCode.P1001, CursorCodec.validateLimit(0));
        assertEquals(ErrorCode.P1001, CursorCodec.validateLimit(-5));
        assertEquals(ErrorCode.P1001, CursorCodec.validateLimit(101));
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = CursorCodec.encode(payload(), KEY);
        int dot = token.indexOf('.');
        String payloadPart = token.substring(0, dot);
        // 篡改 payload 中部字符（末字符可能落在 base64 无填充的死位上，不影响解码字节）
        int flipIndex = payloadPart.length() / 2;
        char flipped = payloadPart.charAt(flipIndex) == 'A' ? 'B' : 'A';
        String tampered = payloadPart.substring(0, flipIndex) + flipped
                + payloadPart.substring(flipIndex + 1) + token.substring(dot);
        assertEquals(ErrorCode.P1001, CursorCodec.decode(
                tampered, KEY, ISSUED.plusSeconds(60), FILTER_DIGEST).code());
        // 错误密钥签名不匹配
        assertEquals(ErrorCode.P1001, CursorCodec.decode(token,
                "another-key".getBytes(), ISSUED.plusSeconds(60), FILTER_DIGEST).code());
    }

    @Test
    void expiredCursorIsRejectedAt15Minutes() {
        String token = CursorCodec.encode(payload(), KEY);
        // 恰好 15 分钟：仍有效（issuedAt + VALIDITY 不早于 now）
        assertEquals(ErrorCode.OK, CursorCodec.decode(token, KEY,
                ISSUED.plus(CursorCodec.VALIDITY), FILTER_DIGEST).code());
        // 超 15 分钟：失效（04 §7 过期游标 15 分钟）
        assertEquals(ErrorCode.P1001, CursorCodec.decode(token, KEY,
                ISSUED.plus(CursorCodec.VALIDITY).plusMillis(1), FILTER_DIGEST).code());
    }

    @Test
    void filterChangeForcesRepagination() {
        String token = CursorCodec.encode(payload(), KEY);
        String otherDigest = "fedcba9876543210".repeat(4);
        assertNotEquals(FILTER_DIGEST, otherDigest);
        // 过滤器改变必须重新分页（04 §7）
        assertEquals(ErrorCode.P1001, CursorCodec.decode(token, KEY,
                ISSUED.plusSeconds(60), otherDigest).code());
        assertEquals(ErrorCode.OK, CursorCodec.decode(token, KEY,
                ISSUED.plusSeconds(60), FILTER_DIGEST).code());
    }

    @Test
    void malformedTokensAreRejected() {
        assertEquals(ErrorCode.P1001, CursorCodec.decode(null, KEY,
                ISSUED, FILTER_DIGEST).code());
        assertEquals(ErrorCode.P1001, CursorCodec.decode(" ", KEY,
                ISSUED, FILTER_DIGEST).code());
        assertEquals(ErrorCode.P1001, CursorCodec.decode("no-dot-token", KEY,
                ISSUED, FILTER_DIGEST).code());
        assertEquals(ErrorCode.P1001, CursorCodec.decode("a.", KEY,
                ISSUED, FILTER_DIGEST).code());
        assertEquals(ErrorCode.P1001, CursorCodec.decode(
                "x".repeat(CursorCodec.MAX_TOKEN_LENGTH + 1), KEY, ISSUED, FILTER_DIGEST).code());
        // base64 非法字符
        assertEquals(ErrorCode.P1001, CursorCodec.decode("!!@#$^.%%", KEY,
                ISSUED, FILTER_DIGEST).code());
    }

    @Test
    void missingSigningKeyFailsFastOnEncode() {
        assertThrows(IllegalStateException.class, () -> CursorCodec.encode(payload(), null));
        assertThrows(IllegalStateException.class,
                () -> CursorCodec.encode(payload(), new byte[0]));
    }

    @Test
    void differentSubjectsProduceDifferentTokens() {
        String tokenA = CursorCodec.encode(payload(), KEY);
        String tokenB = CursorCodec.encode(new Payload("2026-11-12T00:00:00Z", LAST_ID,
                FILTER_DIGEST,
                UUID.fromString("4c9e1a3d-6f5b-4e4c-8d7a-8b9c0d1e2f3a"), ISSUED), KEY);
        assertNotEquals(tokenA, tokenB);
        // filterDigest 便捷函数稳定
        assertEquals(CursorCodec.filterDigest("a=1,b=2"), CursorCodec.filterDigest("a=1,b=2"));
        assertNotEquals(CursorCodec.filterDigest("a=1,b=2"), CursorCodec.filterDigest("a=1,b=3"));
    }
}
