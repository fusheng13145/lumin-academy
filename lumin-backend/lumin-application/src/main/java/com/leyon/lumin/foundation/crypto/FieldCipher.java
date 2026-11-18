package com.leyon.lumin.foundation.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 分级字段加密（docs/13 UGC-007；04 §5.2 basis_ref_cipher / §3 birth_date_cipher）：
 * AES-256-GCM（96 位随机 IV + 128 位认证标签），密文自包含 IV；
 * AAD 绑定字段用途（防跨字段密文互换）；密钥由配置注入
 * （生产经 Secret Manager，`LUMIN_CRYPTO_KEY`），缺钥 fail-fast。
 * **登记**：密钥经 SHA-256 派生（本地开发占位口径）；生产轮换与 KMS 托管随部署批次。
 */
@Component
public class FieldCipher {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    public FieldCipher(@Value("${lumin.crypto.key:${LUMIN_CRYPTO_KEY:lumin-crypto-dev-key}}")
                       String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            // UGC-007：敏感字段加密密钥缺失属部署错误，fail-fast（与游标密钥同口径）
            throw new IllegalStateException("字段加密密钥缺失（Secret Manager 注入失败）");
        }
        try {
            byte[] derived = MessageDigest.getInstance("SHA-256")
                    .digest(configuredKey.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(derived, "AES");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }

    /** 加密：返回 [IV(12) | 密文+认证标签]；aad 绑定字段用途（不可复用于其他字段）。 */
    public byte[] encrypt(String plaintext, String aad) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null) {
                cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            }
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = Arrays.copyOf(iv, IV_LENGTH + sealed.length);
            System.arraycopy(sealed, 0, out, IV_LENGTH, sealed.length);
            return out;
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("字段加密失败", e);
        }
    }

    /** 解密：密文被篡改 / AAD 不匹配 / 密钥错误 → IllegalStateException（拒绝，不静默）。 */
    public String decrypt(byte[] sealed, String aad) {
        if (sealed == null || sealed.length <= IV_LENGTH) {
            throw new IllegalStateException("字段密文长度非法");
        }
        try {
            byte[] iv = Arrays.copyOf(sealed, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null) {
                cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            }
            return new String(cipher.doFinal(Arrays.copyOfRange(sealed, IV_LENGTH,
                    sealed.length)), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException e) {
            // GCM 认证失败（篡改/AAD 不符/密钥错误）一律拒绝，不静默降级
            throw new IllegalStateException("字段密文校验失败（篡改或密钥不符）", e);
        }
    }
}
