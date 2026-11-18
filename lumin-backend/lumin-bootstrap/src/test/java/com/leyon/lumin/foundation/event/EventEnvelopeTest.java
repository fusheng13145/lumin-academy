package com.leyon.lumin.foundation.event;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.event.EventEnvelope.Envelope;
import com.leyon.lumin.foundation.values.DomainEventType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 事件信封校验与乱序防护（02 §5.2 / 03 §4.3；R15 基础）。 */
class EventEnvelopeTest {

    private static final Instant BUSINESS_TIME = Instant.parse("2026-11-11T16:00:00Z");

    private static Envelope validEnvelope(long revision) {
        return new Envelope(UUID.fromString("0f8fa2b4-93c1-4a1f-9a3e-2b7c6d5e4f31"),
                DomainEventType.CONTENT_SUBMITTED_V1, "content_version",
                UUID.fromString("1f8fa2b4-93c1-4a1f-9a3e-2b7c6d5e4f32"), revision,
                BUSINESS_TIME, "trace-0001", "{\"k\":\"v\"}");
    }

    @Test
    void validEnvelopePasses() {
        assertEquals(ErrorCode.OK, EventEnvelope.validate(validEnvelope(1)));
    }

    @Test
    void nullOrBlankFieldsAreRejected() {
        assertEquals(ErrorCode.P1001, EventEnvelope.validate(null));
        assertEquals(ErrorCode.P1001, EventEnvelope.validate(new Envelope(null,
                DomainEventType.CONTENT_SUBMITTED_V1, "content_version",
                UUID.randomUUID(), 1, BUSINESS_TIME, "t", "{}")));
        assertEquals(ErrorCode.P1001, EventEnvelope.validate(new Envelope(UUID.randomUUID(),
                null, "content_version", UUID.randomUUID(), 1, BUSINESS_TIME, "t", "{}")));
        assertEquals(ErrorCode.P1001, EventEnvelope.validate(new Envelope(UUID.randomUUID(),
                DomainEventType.CONTENT_SUBMITTED_V1, " ", UUID.randomUUID(), 1,
                BUSINESS_TIME, "t", "{}")));
        assertEquals(ErrorCode.P1001, EventEnvelope.validate(new Envelope(UUID.randomUUID(),
                DomainEventType.CONTENT_SUBMITTED_V1, "content_version", null, 1,
                BUSINESS_TIME, "t", "{}")));
        assertEquals(ErrorCode.P1001, EventEnvelope.validate(new Envelope(UUID.randomUUID(),
                DomainEventType.CONTENT_SUBMITTED_V1, "content_version", UUID.randomUUID(), 1,
                null, "t", "{}")));
        assertEquals(ErrorCode.P1001, EventEnvelope.validate(new Envelope(UUID.randomUUID(),
                DomainEventType.CONTENT_SUBMITTED_V1, "content_version", UUID.randomUUID(), 1,
                BUSINESS_TIME, " ", "{}")));
        assertEquals(ErrorCode.P1001, EventEnvelope.validate(new Envelope(UUID.randomUUID(),
                DomainEventType.CONTENT_SUBMITTED_V1, "content_version", UUID.randomUUID(), 1,
                BUSINESS_TIME, "t", " ")));
    }

    @Test
    void schemaVersionAndRevisionBoundsAreEnforced() {
        // schemaVersion 固定 1（02 §5.2）：由常量承载，非法 revision 直接拒绝
        assertEquals(ErrorCode.P1001, EventEnvelope.validate(validEnvelope(0)));
        assertEquals(ErrorCode.P1001, EventEnvelope.validate(validEnvelope(-1)));
        assertEquals(ErrorCode.P1001,
                EventEnvelope.validate(validEnvelope(EventEnvelope.MAX_REVISION + 1)));
        assertEquals(ErrorCode.OK,
                EventEnvelope.validate(validEnvelope(EventEnvelope.MAX_REVISION)));
    }

    @Test
    void traceIdAndAggregateTypeLengthBounds() {
        assertEquals(ErrorCode.P1001, EventEnvelope.validate(new Envelope(UUID.randomUUID(),
                DomainEventType.CONTENT_SUBMITTED_V1, "a".repeat(41), UUID.randomUUID(), 1,
                BUSINESS_TIME, "t", "{}")));
        assertEquals(ErrorCode.P1001, EventEnvelope.validate(new Envelope(UUID.randomUUID(),
                DomainEventType.CONTENT_SUBMITTED_V1, "content_version", UUID.randomUUID(), 1,
                BUSINESS_TIME, "x".repeat(65), "{}")));
        assertEquals(ErrorCode.OK, EventEnvelope.validate(new Envelope(UUID.randomUUID(),
                DomainEventType.CONTENT_SUBMITTED_V1, "a".repeat(40), UUID.randomUUID(), 1,
                BUSINESS_TIME, "x".repeat(64), "{}")));
    }

    @Test
    void staleEventsAreRejectedByRevision() {
        // 03 §4.3：乱序以 aggregateRevision 拒绝旧事件；重复（等值）由 inbox 去重
        Envelope older = validEnvelope(4);
        Envelope newer = validEnvelope(6);
        assertTrue(EventEnvelope.isStale(older, 5L));
        assertFalse(EventEnvelope.isStale(newer, 5L));
        assertFalse(EventEnvelope.isStale(validEnvelope(5), 5L));
        assertFalse(EventEnvelope.isStale(older, null));
        assertFalse(EventEnvelope.isStale(newer, 0L));
    }
}
