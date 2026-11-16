package com.leyon.lumin.foundation.event;

import com.leyon.lumin.foundation.repository.OutboxEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Outbox 投递器（03 §4.3 / INV-06）：扫描未投递事件分发至订阅端口；
 * 成功记 delivered_at；失败退避 1/5/30/60s（{@link OutboxBackoff}），
 * 事件不删除；累计超 24h 告警转人工处置。至少一次投递：消费侧以
 * inbox_receipt 去重（重复投递不重复执行领域动作）。
 * 定时触发由 {@link OutboxScheduler} 承担（测试可直调本方法，确定性执行）。
 */
@Component
public class OutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private static final int BATCH_LIMIT = 50;
    /** 累计退避超过 24h → 人工处置告警（03 §4.3）。 */
    private static final Duration MANUAL_HANDOFF_AFTER = Duration.ofHours(24);

    private final OutboxEventMapper outboxMapper;
    private final List<DomainEventSubscriber> subscribers;
    private final TransactionTemplate transactionTemplate;

    public OutboxDispatcher(OutboxEventMapper outboxMapper,
                            List<DomainEventSubscriber> subscribers,
                            PlatformTransactionManager transactionManager) {
        this.outboxMapper = outboxMapper;
        this.subscribers = subscribers;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** 单轮投递：返回本轮成功投递数。 */
    public int deliverPending() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<OutboxEventRow> pending = outboxMapper.selectPending(now, BATCH_LIMIT);
        int delivered = 0;
        for (OutboxEventRow event : pending) {
            try {
                dispatch(event);
                outboxMapper.markDelivered(event.id(), LocalDateTime.now(ZoneOffset.UTC),
                        "outbox-dispatcher");
                delivered++;
            } catch (Exception e) {
                failWithBackoff(event, e);
            }
        }
        return delivered;
    }

    /** 分发：无消费者支持的事件类型登记后标记投递（事件类型随批次落地消费者）。 */
    private void dispatch(OutboxEventRow event) {
        List<DomainEventSubscriber> matched = subscribers.stream()
                .filter(subscriber -> subscriber.supportedEventTypes()
                        .contains(event.eventType()))
                .toList();
        if (matched.isEmpty()) {
            log.warn("事件无消费者，暂不投递 event_id={} type={}（消费者随批次落地）",
                    event.eventId(), event.eventType());
            return;
        }
        transactionTemplate.execute(status -> {
            for (DomainEventSubscriber subscriber : matched) {
                subscriber.handle(UUID.fromString(event.eventId()), event.payload());
            }
            return null;
        });
    }

    private void failWithBackoff(OutboxEventRow event, Exception cause) {
        int attempts = (event.attempts() == null ? 0 : event.attempts()) + 1;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int delay = OutboxBackoff.nextRetryDelaySeconds(attempts);
        outboxMapper.recordFailure(event.id(), attempts,
                now.plusSeconds(delay), now, "outbox-dispatcher");
        log.warn("事件投递失败 attempts={} next_retry={}s event_id={} cause={}",
                attempts, delay, event.eventId(), cause.getMessage());
        if (cumulativeBackoffSeconds(attempts) > MANUAL_HANDOFF_AFTER.toSeconds()) {
            log.error("事件累计退避超 24h，转人工处置（不删除事件）event_id={}",
                    event.eventId());
        }
    }

    /** 累计退避秒数（1+5+30 后每 60s），用于 24h 人工处置判定。 */
    static long cumulativeBackoffSeconds(int attempts) {
        long total = 0;
        for (int i = 1; i <= attempts; i++) {
            total += OutboxBackoff.nextRetryDelaySeconds(i);
        }
        return total;
    }
}
