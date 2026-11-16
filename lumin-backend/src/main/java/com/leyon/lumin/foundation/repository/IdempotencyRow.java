package com.leyon.lumin.foundation.repository;

import java.time.LocalDateTime;

/** idempotency_record 表行记录（docs/04 §5.1；复合主键 subject_id+method+path+key）。 */
public record IdempotencyRow(
        String subjectId,
        String method,
        String path,
        String key,
        String requestDigest,
        Integer responseCode,
        String responseBody,
        LocalDateTime expiresAt) {
}
