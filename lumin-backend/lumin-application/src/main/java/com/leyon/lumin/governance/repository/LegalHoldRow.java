package com.leyon.lumin.governance.repository;

import java.time.LocalDateTime;

/** legal_hold 表行记录（docs/04 §5.2 governance；basis_ref_cipher 加密原始依据）。 */
public record LegalHoldRow(
        String id,
        String subjectType,
        String subjectId,
        byte[] basisRefCipher,
        String state,
        LocalDateTime expiresAt,
        String ownerId,
        String reason,
        LocalDateTime releasedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
