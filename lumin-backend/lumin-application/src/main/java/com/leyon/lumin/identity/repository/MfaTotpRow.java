package com.leyon.lumin.identity.repository;

import java.time.LocalDateTime;

/**
 * TOTP 凭据行（V21）：{@code secretCipher} 为 FieldCipher 密文（AAD {@code mfa.totp.secret}），
 * 库里**没有明文种子**；{@code lastUsedStep} 用于重放保护。
 */
public record MfaTotpRow(
        String id,
        String subject,
        byte[] secretCipher,
        String status,
        Long lastUsedStep,
        LocalDateTime activatedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String createdBy,
        String updatedBy,
        Long lockVersion) {
}
