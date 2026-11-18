package com.leyon.lumin.foundation.event;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Outbox 定时投递（03 §4.3：扫描间隔 1 秒）。仅当 `lumin.outbox.enabled=true`
 * 时创建——集成测试显式关闭调度、直调 {@link OutboxDispatcher#deliverPending()}
 * 保证确定性；生产配置开启。
 */
@Component
@ConditionalOnProperty(name = "lumin.outbox.enabled", havingValue = "true")
public class OutboxScheduler {

    private final OutboxDispatcher dispatcher;

    public OutboxScheduler(OutboxDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${lumin.outbox.interval-ms:1000}")
    public void deliverPending() {
        dispatcher.deliverPending();
    }
}
