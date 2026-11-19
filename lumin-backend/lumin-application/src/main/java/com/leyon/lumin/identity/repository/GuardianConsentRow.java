package com.leyon.lumin.identity.repository;

import java.time.LocalDateTime;

/** guardian_consent 表行记录（docs/04 §3 identity；仅同意双方可见，不外泄给管理员）。 */
public record GuardianConsentRow(
        String id,
        String guardianId,
        String childId,
        String proofDigest,
        byte[] proofRefCipher,
        String scope,
        LocalDateTime grantedAt,
        LocalDateTime revokedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
