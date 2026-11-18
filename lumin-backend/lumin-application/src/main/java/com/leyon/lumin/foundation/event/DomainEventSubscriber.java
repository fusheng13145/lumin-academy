package com.leyon.lumin.foundation.event;

import java.util.Set;
import java.util.UUID;

/**
 * 领域事件订阅端口（02 §5.2：消费者以 consumerName+eventId 去重）。
 * 各域实现本端口并在自身事务内完成「领域更新 + inbox_receipt 写入」；
 * 事件载荷按不可信输入处理（INV-07），字段以权威记录核验。
 */
public interface DomainEventSubscriber {

    /** 消费者标识（inbox_receipt U(consumer_name,event_id) 去重键）。 */
    String consumerName();

    /** 订阅的事件类型白名单（DomainEventType.name()）。 */
    Set<String> supportedEventTypes();

    /**
     * 消费一条事件（同一事务内写收据与领域更新）。重复投递经收据唯一约束
     * 去重后直接返回，不重复执行领域动作。
     */
    void handle(UUID eventId, String payloadJson);
}
