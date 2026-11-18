package com.leyon.lumin.identity.repository;

import java.time.LocalDateTime;

/** user_profile 表行记录（docs/04 §3 identity；PERSONAL 级字段不外泄——出生日期仅密文）。 */
public record UserProfileRow(
        String id,
        String oidcIssuer,
        String oidcSubject,
        String nickname,
        byte[] birthDateCipher,
        String declaredAgeBand,
        String verifiedAgeBand,
        String effectiveAgeBand,
        LocalDateTime ageVerifiedAt,
        String accountStatus,
        Long policyRevision,
        LocalDateTime closedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion,
        LocalDateTime deletedAt) {
}
