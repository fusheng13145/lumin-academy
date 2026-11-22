package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.crypto.FieldCipher;
import com.leyon.lumin.identity.domain.Base32;
import com.leyon.lumin.identity.domain.Totp;
import com.leyon.lumin.identity.repository.MfaRecoveryCodeMapper;
import com.leyon.lumin.identity.repository.MfaTotpMapper;
import com.leyon.lumin.identity.repository.MfaTotpRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 二次验证（TOTP）服务（2026-11-20 安全审查 05 项"管理员额外要求"）。
 *
 * <p>链路：登记（生成种子，返回 otpauth URI）→ 确认（校验一次验证码后生效，
 * 同时下发一次性恢复码）→ 登录挑战（校验验证码/恢复码）。
 *
 * <p>安全口径：
 * <ul>
 *   <li>种子只以 {@link FieldCipher} 密文落库（AAD {@code mfa.totp.secret}）；</li>
 *   <li>验证码重放保护：{@code last_used_step} 单调前进，同一时间步只能用一次；</li>
 *   <li>恢复码只存 SHA-256 摘要、单次使用（{@code used_at} 置位后失效）；</li>
 *   <li>已 ACTIVE 的凭据不允许重新登记（避免被会话劫持者替换种子）。</li>
 * </ul>
 */
@Service
public class MfaTotpService {

    private static final Logger log = LoggerFactory.getLogger(MfaTotpService.class);

    /** 种子密文的 AAD 用途绑定（防跨字段密文互换）。 */
    private static final String SECRET_AAD = "mfa.totp.secret";
    /** OTPAuth 发行方（显示在 Authenticator App 中）。 */
    private static final String ISSUER = "Lumin Academy";
    /** 恢复码数量。 */
    private static final int RECOVERY_CODE_COUNT = 8;
    /** 恢复码字符集（去掉易混淆的 0/O/1/I）。 */
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final MfaTotpMapper totpMapper;
    private final MfaRecoveryCodeMapper recoveryMapper;
    private final FieldCipher fieldCipher;

    public MfaTotpService(MfaTotpMapper totpMapper, MfaRecoveryCodeMapper recoveryMapper,
                          FieldCipher fieldCipher) {
        this.totpMapper = totpMapper;
        this.recoveryMapper = recoveryMapper;
        this.fieldCipher = fieldCipher;
    }

    /** 登记回执（secret 与 otpauthUri 只在登记时返回一次）。 */
    public record Enrollment(String secretBase32, String otpauthUri, String status) {
    }

    /** 确认回执（恢复码只在确认时返回一次，服务端只留摘要）。 */
    public record Confirmation(List<String> recoveryCodes) {
    }

    /** 登记：生成新种子并以 PENDING 落库（已 ACTIVE 则拒绝，需先走运维解密流程）。 */
    @Transactional
    public Enrollment enroll(String subject, String accountLabel, LocalDateTime now) {
        MfaTotpRow existing = totpMapper.findBySubject(subject);
        if (existing != null && "ACTIVE".equals(existing.status())) {
            log.warn("二次验证登记被拒 event=mfa.enroll result=rejected reason=already_active "
                    + "subject={}", subject);
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        byte[] secret = Totp.generateSecret();
        String secretBase32 = Base32.encode(secret);
        byte[] secretCipher = fieldCipher.encrypt(secretBase32, SECRET_AAD);
        if (existing == null) {
            MfaTotpRow row = new MfaTotpRow(UUID.randomUUID().toString(), subject, secretCipher,
                    "PENDING", null, null, now, now, subject, subject, 1L);
            totpMapper.insert(row);
        } else {
            totpMapper.updatePendingSecret(existing.id(), subject, secretCipher, now);
        }
        log.info("二次验证登记 event=mfa.enroll result=ok subject={} status=PENDING", subject);
        return new Enrollment(secretBase32, otpauthUri(accountLabel, secretBase32), "PENDING");
    }

    /** 确认：校验一次验证码 → ACTIVE，并下发一次性恢复码。 */
    @Transactional
    public Confirmation confirm(String subject, String code, LocalDateTime now) {
        MfaTotpRow row = subject == null ? null : totpMapper.findBySubject(subject);
        if (row == null || !"PENDING".equals(row.status())) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        byte[] secret = Base32.decode(fieldCipher.decrypt(row.secretCipher(), SECRET_AAD));
        Long step = Totp.verify(secret, normalizeCode(code), now.toEpochSecond(ZoneOffset.UTC),
                null);
        if (step == null) {
            log.warn("二次验证确认失败 event=mfa.confirm result=rejected reason=bad_code "
                    + "subject={}", subject);
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        totpMapper.activate(row.id(), subject, now);
        List<String> plainCodes = generateRecoveryCodes();
        for (String plain : plainCodes) {
            recoveryMapper.insert(UUID.randomUUID().toString(), subject,
                    sha256(normalizeCode(plain)), now);
        }
        log.info("二次验证生效 event=mfa.confirm result=ok subject={} recoveryCodes={}",
                subject, plainCodes.size());
        return new Confirmation(plainCodes);
    }

    /** 是否已启用（登录路径据此决定"直接签发会话"还是"要求二次验证"）。 */
    @Transactional(readOnly = true)
    public boolean isActive(String subject) {
        MfaTotpRow row = subject == null ? null : totpMapper.findBySubject(subject);
        return row != null && "ACTIVE".equals(row.status());
    }

    /** 校验验证码或恢复码（验证码命中后推进 last_used_step，防同码二次使用）。 */
    @Transactional
    public boolean verify(String subject, String code, LocalDateTime now) {
        MfaTotpRow row = subject == null ? null : totpMapper.findBySubject(subject);
        if (row == null || !"ACTIVE".equals(row.status())) {
            return false;
        }
        String normalized = normalizeCode(code);
        byte[] secret = Base32.decode(fieldCipher.decrypt(row.secretCipher(), SECRET_AAD));
        Long step = Totp.verify(secret, normalized, now.toEpochSecond(ZoneOffset.UTC),
                row.lastUsedStep());
        if (step != null) {
            totpMapper.markStepUsed(row.id(), step, now);
            return true;
        }
        // 恢复码：单次使用
        String codeHash = sha256(normalized);
        String recoveryId = recoveryMapper.findUnusedId(subject, codeHash);
        if (recoveryId != null && recoveryMapper.markUsed(recoveryId, now) == 1) {
            log.warn("使用恢复码登录 event=mfa.recovery result=used subject={}", subject);
            return true;
        }
        return false;
    }

    /** 生成 otpauth URI（供 Authenticator App 扫码/手输）。 */
    private static String otpauthUri(String accountLabel, String secretBase32) {
        String label = urlEncode(ISSUER + ":" + (accountLabel == null ? "user" : accountLabel));
        return "otpauth://totp/" + label + "?secret=" + secretBase32
                + "&issuer=" + urlEncode(ISSUER) + "&algorithm=SHA1&digits=" + Totp.DIGITS
                + "&period=" + Totp.PERIOD_SECONDS;
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** 恢复码：10 位随机（字符集去混淆），展示为 5-5 分段。 */
    private static List<String> generateRecoveryCodes() {
        List<String> codes = new ArrayList<>(RECOVERY_CODE_COUNT);
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            StringBuilder builder = new StringBuilder(11);
            for (int j = 0; j < 10; j++) {
                if (j == 5) {
                    builder.append('-');
                }
                builder.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(
                        RECOVERY_ALPHABET.length())));
            }
            codes.add(builder.toString());
        }
        return codes;
    }

    /** 归一化：大写、去掉分隔符（验证码与恢复码同一口径）。 */
    private static String normalizeCode(String code) {
        if (code == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                builder.append(Character.toUpperCase(c));
            }
        }
        return builder.toString().toUpperCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }
}
