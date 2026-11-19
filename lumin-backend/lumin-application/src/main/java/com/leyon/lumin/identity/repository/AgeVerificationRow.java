package com.leyon.lumin.identity.repository;

import java.time.LocalDateTime;

/** age_verification 表行记录（docs/04 §3 identity；proofRef 仅密文，摘要供唯一约束）。 */
public record AgeVerificationRow(
        String id,
        String userId,
        String provider,
        byte[] proofRefCipher,
        String proofDigest,
        String method,
        String result,
        String ageBand,
        LocalDateTime verifiedAt,
        LocalDateTime expiresAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
