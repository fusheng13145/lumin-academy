package com.leyon.lumin.identity.repository;

import java.time.LocalDateTime;

/** rights_request 表行记录（docs/04 §5.2 identity；result_ref 经 FieldCipher 加密）。 */
public record RightsRequestRow(
        String id,
        String userId,
        String kind,
        String state,
        String reason,
        LocalDateTime dueAt,
        String holdId,
        byte[] resultRefCipher,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
