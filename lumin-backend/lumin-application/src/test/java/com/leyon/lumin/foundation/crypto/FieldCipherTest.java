package com.leyon.lumin.foundation.crypto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分级字段加密单元测试（docs/13 UGC-007；04 §5.2）：
 * AES-256-GCM 加解密回环；AAD 绑定字段用途（跨字段互换拒绝）；密文含随机 IV
 * 且不含明文（非 UTF-8 字节伪装加密）；密文篡改/密钥错误拒绝；密钥缺失 fail-fast。
 */
class FieldCipherTest {

    private static final String AAD = "legal_hold.basis_ref_cipher";

    @Test
    void roundTripAndCrossFieldAadBinding() {
        FieldCipher cipher = new FieldCipher("dev-secret");
        byte[] sealed = cipher.encrypt("诉讼证据保全依据-2026", AAD);
        assertThat(cipher.decrypt(sealed, AAD)).isEqualTo("诉讼证据保全依据-2026");
        // AAD 不匹配（把本字段密文当其他字段解）→ 拒绝
        assertThatThrownBy(() -> cipher.decrypt(sealed, "user_profile.birth_date_cipher"))
                .isInstanceOf(IllegalStateException.class);
        // 密文随机 IV：同一明文两次加密产出不同密文（非确定性，防频率分析）
        byte[] again = cipher.encrypt("诉讼证据保全依据-2026", AAD);
        assertThat(again).isNotEqualTo(sealed);
    }

    @Test
    void ciphertextIsNotPlausiblePlaintextAndTamperIsRejected() {
        FieldCipher cipher = new FieldCipher("dev-secret");
        String plaintext = "hold-basis:诉讼证据";
        byte[] sealed = cipher.encrypt(plaintext, AAD);
        // 密文不含明文字节（非 UTF-8 字节伪装加密——EV-13 整改口径）
        assertThat(new String(sealed, StandardCharsets.UTF_8)).doesNotContain(plaintext);
        // 篡改密文（翻转一比特）→ 认证失败拒绝
        byte[] tampered = sealed.clone();
        tampered[tampered.length - 1] ^= 0x01;
        assertThatThrownBy(() -> cipher.decrypt(tampered, AAD))
                .isInstanceOf(IllegalStateException.class);
        // 截断 → 拒绝
        assertThatThrownBy(() -> cipher.decrypt(
                java.util.Arrays.copyOf(sealed, sealed.length - 3), AAD))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongKeyAndMissingKeyFailFast() {
        FieldCipher cipherA = new FieldCipher("key-alpha");
        FieldCipher cipherB = new FieldCipher("key-beta");
        byte[] sealed = cipherA.encrypt("机密依据", AAD);
        // 密钥错误 → 认证失败拒绝
        assertThatThrownBy(() -> cipherB.decrypt(sealed, AAD))
                .isInstanceOf(IllegalStateException.class);
        // 密钥缺失 → 构造即 fail-fast（部署错误）
        assertThatThrownBy(() -> new FieldCipher("  "))
                .isInstanceOf(IllegalStateException.class);
    }
}
