package com.leyon.lumin.foundation.repository;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** inbox_receipt 表数据访问（04 §5.1；消费者事务内写入，U(consumer_name,event_id) 去重兜底）。 */
@Mapper
public interface InboxReceiptMapper {

    @Insert("""
            INSERT INTO inbox_receipt (consumer_name, event_id, processed_at)
            VALUES (#{row.consumerName}, #{row.eventId}, #{row.processedAt})
            """)
    int insert(@Param("row") InboxReceiptRow row);
}
