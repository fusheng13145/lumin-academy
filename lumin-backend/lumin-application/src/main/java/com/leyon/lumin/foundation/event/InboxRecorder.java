package com.leyon.lumin.foundation.event;

import com.leyon.lumin.foundation.repository.InboxReceiptMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * Inbox 收据记录门面（02 §5.2：消费者以 consumerName+eventId 去重；
 * 唯一约束兜底，重复投递由调用方捕获 DuplicateKeyException 处理）。
 */
@Component
public class InboxRecorder {

    private final InboxReceiptMapper mapper;

    public InboxRecorder(InboxReceiptMapper mapper) {
        this.mapper = mapper;
    }

    /** 消费者事务内写收据（03 §4.3）。 */
    public void record(String consumerName, String eventId, LocalDateTime processedAt) {
        mapper.insert(new InboxReceiptRow(consumerName, eventId, processedAt));
    }
}
