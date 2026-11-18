package com.leyon.lumin.foundation.event;

import com.leyon.lumin.foundation.repository.OutboxEventMapper;
import org.springframework.stereotype.Component;

/**
 * Outbox 事件追加门面（02 §3.1 foundation 事件信封职责；各业务域经此写事件，
 * 不跨模块访问 foundation.repository Mapper——02 §3.2）。
 */
@Component
public class OutboxAppender {

    private final OutboxEventMapper mapper;

    public OutboxAppender(OutboxEventMapper mapper) {
        this.mapper = mapper;
    }

    /** 追加一条事件（与业务状态同事务由调用方承载，INV-06）。 */
    public void append(OutboxEventRow row) {
        mapper.insert(row);
    }
}
