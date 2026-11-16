package com.leyon.lumin.foundation.idempotency;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 幂等作用域、请求摘要与 24h 过期（03 §4.1 第 3 条 / 04 §5.1 idempotency_record；T09 前置规则）。 */
class IdempotencyRulesTest {

    @Test
    void keyMustBeUuid() {
        assertEquals(ErrorCode.OK,
                IdempotencyRules.validateKey(UUID.randomUUID().toString()));
        assertEquals(ErrorCode.P1001, IdempotencyRules.validateKey("not-a-uuid"));
        assertEquals(ErrorCode.P1001, IdempotencyRules.validateKey(" "));
        assertEquals(ErrorCode.P1001, IdempotencyRules.validateKey(null));
    }

    @Test
    void onlyWriteMethodsAreIdempotent() {
        assertTrue(IdempotencyRules.isIdempotentMethod("POST"));
        assertTrue(IdempotencyRules.isIdempotentMethod("PUT"));
        assertTrue(IdempotencyRules.isIdempotentMethod("PATCH"));
        assertFalse(IdempotencyRules.isIdempotentMethod("GET"));
        assertFalse(IdempotencyRules.isIdempotentMethod("DELETE"));
        assertFalse(IdempotencyRules.isIdempotentMethod(null));
    }

    @Test
    void pathNormalization() {
        assertEquals("/api/v1/drafts", IdempotencyRules.normalizePath("/api/v1/drafts"));
        assertEquals("/api/v1/drafts", IdempotencyRules.normalizePath("/api/v1/drafts/"));
        assertEquals("/api/v1/drafts", IdempotencyRules.normalizePath("/api/v1/drafts?id=1"));
        assertEquals("/api/v1/drafts", IdempotencyRules.normalizePath("/api/v1/drafts#frag"));
        assertEquals("/api/v1/x/y", IdempotencyRules.normalizePath("//api//v1//x//y"));
        assertEquals("/", IdempotencyRules.normalizePath("/"));
        assertEquals(null, IdempotencyRules.normalizePath(null));
    }

    @Test
    void requestDigestMatchesKnownSha256Vector() {
        // SHA-256("abc") 标准测试向量
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                IdempotencyRules.requestDigest("abc"));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                IdempotencyRules.requestDigest("abc".getBytes(StandardCharsets.UTF_8)));
        // 同键不同载荷（T09：同键改正文 → B2003 由服务层依据摘要差异判定）
        assertNotEquals(IdempotencyRules.requestDigest("第一段"),
                IdempotencyRules.requestDigest("第二段"));
        assertEquals(64, IdempotencyRules.requestDigest(new byte[0]).length());
        assertTrue(IdempotencyRules.requestDigest(new byte[0])
                .matches("[a-f0-9]{64}"));
    }

    @Test
    void ttlIs24HoursAndExpiryBoundaryIsInclusive() {
        Instant created = Instant.parse("2026-11-11T16:00:00Z");
        Instant expires = IdempotencyRules.expiresAt(created);
        assertEquals(Instant.parse("2026-11-12T16:00:00Z"), expires);
        // 保留期内可回放（T09：100 次并发提交响应引用一致）
        assertFalse(IdempotencyRules.isExpired(expires,
                Instant.parse("2026-11-12T15:59:59Z")));
        // 到期当刻起失效（[created, created+24h) 保留）
        assertTrue(IdempotencyRules.isExpired(expires,
                Instant.parse("2026-11-12T16:00:00Z")));
        assertTrue(IdempotencyRules.isExpired(expires,
                Instant.parse("2026-11-12T16:00:01Z")));
        // 输入缺失按失效处理（fail-closed，不回放）
        assertTrue(IdempotencyRules.isExpired(null, created));
        assertTrue(IdempotencyRules.isExpired(expires, null));
    }
}
