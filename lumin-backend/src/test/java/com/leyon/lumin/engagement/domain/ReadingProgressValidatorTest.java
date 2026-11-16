package com.leyon.lumin.engagement.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 阅读进度边界（04 §5.1 / 05 §3.4；T29：位置 0/10000/-1/10001、未来 +6 分钟）。 */
class ReadingProgressValidatorTest {

    private static final Instant SERVER_NOW = Instant.parse("2026-11-11T16:00:00Z");

    @Test
    void positionBoundaries() {
        assertEquals(ErrorCode.OK, ReadingProgressValidator.validate(
                0, 60, SERVER_NOW, SERVER_NOW));
        assertEquals(ErrorCode.OK, ReadingProgressValidator.validate(
                10000, 0, SERVER_NOW, SERVER_NOW));
        assertEquals(ErrorCode.P1001, ReadingProgressValidator.validate(
                -1, 60, SERVER_NOW, SERVER_NOW));
        assertEquals(ErrorCode.P1001, ReadingProgressValidator.validate(
                10001, 60, SERVER_NOW, SERVER_NOW));
        assertEquals(ErrorCode.P1001, ReadingProgressValidator.validate(
                null, 60, SERVER_NOW, SERVER_NOW));
    }

    @Test
    void readSecondsMustBeNonNegative() {
        assertEquals(ErrorCode.OK, ReadingProgressValidator.validate(
                5000, 0, SERVER_NOW, SERVER_NOW));
        assertEquals(ErrorCode.P1001, ReadingProgressValidator.validate(
                5000, -1, SERVER_NOW, SERVER_NOW));
        assertEquals(ErrorCode.P1001, ReadingProgressValidator.validate(
                5000, null, SERVER_NOW, SERVER_NOW));
    }

    @Test
    void clientClockMayLeadByAtMostFiveMinutes() {
        // 恰好 +5 分钟：允许（04 §5.1：拒绝「未来超过 5 分钟」）
        assertEquals(ErrorCode.OK, ReadingProgressValidator.validate(
                5000, 60, SERVER_NOW.plusSeconds(300), SERVER_NOW));
        // T29：未来 +6 分钟 → 拒绝
        assertEquals(ErrorCode.P1001, ReadingProgressValidator.validate(
                5000, 60, SERVER_NOW.plusSeconds(360), SERVER_NOW));
        // 过去时间：合法
        assertEquals(ErrorCode.OK, ReadingProgressValidator.validate(
                5000, 60, SERVER_NOW.minusSeconds(86400), SERVER_NOW));
    }

    @Test
    void nullInputsAreRejected() {
        assertEquals(ErrorCode.P1001, ReadingProgressValidator.validate(
                5000, 60, null, SERVER_NOW));
        assertEquals(ErrorCode.P1001, ReadingProgressValidator.validate(
                5000, 60, SERVER_NOW, null));
    }
}
