package com.leyon.lumin.foundation.event;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Outbox 投递退避与 24h 人工处置（03 §4.3）。 */
class OutboxBackoffTest {

    @Test
    void backoffSequenceIs1Then5Then30Then60() {
        assertEquals(1, OutboxBackoff.nextRetryDelaySeconds(1));
        assertEquals(5, OutboxBackoff.nextRetryDelaySeconds(2));
        assertEquals(30, OutboxBackoff.nextRetryDelaySeconds(3));
        assertEquals(60, OutboxBackoff.nextRetryDelaySeconds(4));
        assertEquals(60, OutboxBackoff.nextRetryDelaySeconds(100));
        // 防御性钳制：非法输入按首次失败处理
        assertEquals(1, OutboxBackoff.nextRetryDelaySeconds(0));
        assertEquals(1, OutboxBackoff.nextRetryDelaySeconds(-3));
    }

    @Test
    void manualHandoffOnlyAfterStrictlyBeyond24h() {
        Instant firstFailure = Instant.parse("2026-11-10T16:00:00Z");
        // 24h 整点仍未「超过」——允许最后一次重试
        assertFalse(OutboxBackoff.needsManualHandoff(firstFailure,
                firstFailure.plus(Duration.ofHours(24))));
        // 超 24h：转人工处置，不删除事件
        assertTrue(OutboxBackoff.needsManualHandoff(firstFailure,
                firstFailure.plus(Duration.ofHours(24)).plusSeconds(1)));
        assertFalse(OutboxBackoff.needsManualHandoff(firstFailure,
                firstFailure.plus(Duration.ofHours(23))));
        // 输入缺失 fail-closed
        assertTrue(OutboxBackoff.needsManualHandoff(null, firstFailure));
        assertTrue(OutboxBackoff.needsManualHandoff(firstFailure, null));
    }
}
