package com.leyon.lumin.foundation.repository;

import java.time.LocalDateTime;

/** inbox_receipt 表行记录（docs/04 §5.1；复合主键 consumer_name+event_id）。 */
public record InboxReceiptRow(
        String consumerName,
        String eventId,
        LocalDateTime processedAt) {
}
