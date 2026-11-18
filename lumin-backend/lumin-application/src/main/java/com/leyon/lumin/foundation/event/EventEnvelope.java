package com.leyon.lumin.foundation.event;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.DomainEventType;

import java.time.Instant;
import java.util.UUID;

/**
 * 事件信封值对象与校验（02 §5.2 / 04 §5.1 outbox_event；foundation 唯一所有）。
 *
 * <p>信封字段：eventId、eventType、schemaVersion=1、aggregateId、aggregateRevision(正整数)、
 * occurredAt(UTC)、traceId、payload。本阶段只建模与校验，不写事件发布代码（U-05）；
 * 事件与业务状态同事务提交由服务层保证（INV-06）。
 */
public final class EventEnvelope {

    /** schemaVersion 固定 1；未知版本进入隔离队列并告警（02 §5.2）。 */
    public static final int SCHEMA_VERSION = 1;

    /** 契约 Revision 上界（2^53−1，与三端 JSON 精度一致）。 */
    public static final long MAX_REVISION = 9007199254740991L;

    public static final int MAX_TRACE_ID_LENGTH = 64;
    public static final int MAX_AGGREGATE_TYPE_LENGTH = 40;

    private EventEnvelope() {
    }

    /**
     * 事件信封（payload 为规范化 JSON 串，由服务层序列化；事件不得含原始 PII，02 §5.2）。
     */
    public record Envelope(UUID eventId, DomainEventType eventType, String aggregateType,
                           UUID aggregateId, long aggregateRevision, Instant occurredAt,
                           String traceId, String payloadJson) {
    }

    /** 校验信封：返回 {@link ErrorCode#OK} 表示合法，否则为拒绝错误码（400/P1001）。 */
    public static ErrorCode validate(Envelope envelope) {
        if (envelope == null
                || envelope.eventId() == null
                || envelope.eventType() == null
                || envelope.aggregateId() == null
                || envelope.occurredAt() == null) {
            return ErrorCode.P1001;
        }
        if (isBlank(envelope.aggregateType())
                || envelope.aggregateType().length() > MAX_AGGREGATE_TYPE_LENGTH) {
            return ErrorCode.P1001;
        }
        if (envelope.aggregateRevision() < 1 || envelope.aggregateRevision() > MAX_REVISION) {
            return ErrorCode.P1001;
        }
        if (isBlank(envelope.traceId()) || envelope.traceId().length() > MAX_TRACE_ID_LENGTH) {
            return ErrorCode.P1001;
        }
        if (isBlank(envelope.payloadJson())) {
            return ErrorCode.P1001;
        }
        return ErrorCode.OK;
    }

    /**
     * 乱序防护（03 §4.3）：状态事件乱序以 aggregateRevision 拒绝旧事件——
     * 新事件 revision 严格小于已处理水位即为旧事件；相等视为重复消息，
     * 由消费者 inbox_receipt 去重（04 §5.1），不在此判定。
     */
    public static boolean isStale(Envelope envelope, Long lastProcessedRevision) {
        return lastProcessedRevision != null
                && envelope.aggregateRevision() < lastProcessedRevision;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
