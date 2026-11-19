package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.crypto.FieldCipher;
import com.leyon.lumin.foundation.values.AgeBand;
import com.leyon.lumin.identity.domain.AgePolicy;
import com.leyon.lumin.identity.port.AgeVerificationPort;
import com.leyon.lumin.identity.repository.AgeVerificationMapper;
import com.leyon.lumin.identity.repository.AgeVerificationRow;
import com.leyon.lumin.identity.repository.UserProfileMapper;
import com.leyon.lumin.identity.repository.UserProfileRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 年龄核验服务（05 §3.1 verifyAge；docs/13 UGC-003）：
 * proofRef 必须由受信适配器回查本人绑定，客户端不得提交核验结论；
 * 核验成功才写 verified/effective 与 age_verified_at，失败只留核验记录不升级。
 * 未接供应商时端口失败关闭 D2001，不以模拟核验冒充生产。
 */
@Service
public class AgeVerificationService {

    private static final String PROOF_REF_AAD = "age_verification.proof_ref";
    private static final String BIRTH_DATE_AAD = "user_profile.birth_date";
    private static final Set<String> METHODS = Set.of("DOCUMENT", "FACE", "GUARDIAN");
    private static final int PROOF_MAX = 200;

    private final UserProfileMapper profileMapper;
    private final AgeVerificationMapper verificationMapper;
    private final AgeVerificationPort verificationPort;
    private final FieldCipher fieldCipher;

    public AgeVerificationService(UserProfileMapper profileMapper,
                                  AgeVerificationMapper verificationMapper,
                                  AgeVerificationPort verificationPort,
                                  FieldCipher fieldCipher) {
        this.profileMapper = profileMapper;
        this.verificationMapper = verificationMapper;
        this.verificationPort = verificationPort;
        this.fieldCipher = fieldCipher;
    }

    /** 契约 Receipt（resourceType=AGE_CHECK）。 */
    public record Receipt(String id, String resourceType, String state, long revision) {
    }

    @Transactional
    public Receipt verify(String subject, String proofRef, LocalDateTime now) {
        String normalizedSubject = requireUuid(subject);
        String normalizedProof = normalizeProofRef(proofRef);
        UserProfileRow profile = profileMapper.findById(normalizedSubject);
        if (profile == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        AgeVerificationPort.Result result = verificationPort.verify(
                normalizedSubject, normalizedProof, now);
        if (result == null) {
            throw new LuminBusinessException(ErrorCode.D2001);
        }
        String provider = requireText(result.provider(), 40);
        String method = requireMethod(result.method());
        String outcome = requireOutcome(result.result());
        LocalDateTime verifiedAt = result.verifiedAt() == null ? now : result.verifiedAt();
        LocalDateTime expiresAt = result.expiresAt() == null
                ? verifiedAt.plusYears(1) : result.expiresAt();
        if (!expiresAt.isAfter(verifiedAt)) {
            throw new LuminBusinessException(ErrorCode.D2001);
        }

        AgeBand verifiedBand = null;
        AgeBand effectiveBand = null;
        if ("VERIFIED".equals(outcome)) {
            verifiedBand = result.ageBand();
            if (verifiedBand == null || verifiedBand == AgeBand.C4) {
                // 供应商不得以 C4/空值宣称已核验；C4 仅表示“未核实”
                throw new LuminBusinessException(ErrorCode.D2001);
            }
            effectiveBand = effectiveBand(profile, verifiedBand, now.toLocalDate());
        }
        AgeBand storedBand = verifiedBand == null ? AgeBand.C4 : verifiedBand;

        String id = UUID.randomUUID().toString();
        String digest = sha256(normalizedProof);
        AgeVerificationRow row = new AgeVerificationRow(id, normalizedSubject, provider,
                fieldCipher.encrypt(normalizedProof, PROOF_REF_AAD), digest, method, outcome,
                storedBand.name(), verifiedAt, expiresAt, now, now, normalizedSubject,
                normalizedSubject, 1L);
        try {
            verificationMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 同一 proofRef 已用于其他请求：不重复消费，409
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        if ("VERIFIED".equals(outcome)) {
            int updated = profileMapper.applyAgeVerification(normalizedSubject,
                    verifiedBand.name(), effectiveBand.name(), verifiedAt, now, normalizedSubject);
            if (updated == 0) {
                throw new LuminBusinessException(ErrorCode.B2004);
            }
        }
        return new Receipt(id, "AGE_CHECK", outcome, 1L);
    }

    private AgeBand effectiveBand(UserProfileRow profile, AgeBand verified, LocalDate today) {
        AgeBand computed = birthBand(profile, today);
        AgeBand strict = stricter(computed, verified);
        AgeBand declared = ageBandOrNull(profile.declaredAgeBand());
        if (declared != null && declared.restrictionRank() < strict.restrictionRank()) {
            // 声明更严格时从严并触发复核；自报更宽不参与升级
            strict = stricter(strict, declared);
        }
        return strict;
    }

    private AgeBand birthBand(UserProfileRow profile, LocalDate today) {
        // 出生日期只以密文落库；解密/解析失败按依赖或数据异常处理，不静默放行
        try {
            String plain = fieldCipher.decrypt(profile.birthDateCipher(), BIRTH_DATE_AAD);
            return AgePolicy.ageBandAt(LocalDate.parse(plain), today);
        } catch (IllegalStateException | DateTimeParseException e) {
            throw new LuminBusinessException(ErrorCode.D2001);
        }
    }

    private static AgeBand stricter(AgeBand left, AgeBand right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return left.restrictionRank() <= right.restrictionRank() ? left : right;
    }

    private static AgeBand ageBandOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return AgeBand.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String requireUuid(String value) {
        if (value == null || value.isBlank()) {
            throw new LuminBusinessException(ErrorCode.A0001);
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException e) {
            throw new LuminBusinessException(ErrorCode.A0001);
        }
    }

    private static String normalizeProofRef(String proofRef) {
        if (proofRef == null) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        String normalized = proofRef.trim();
        if (normalized.isEmpty() || normalized.length() > PROOF_MAX) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        return normalized;
    }

    private static String requireText(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new LuminBusinessException(ErrorCode.D2001);
        }
        return value.trim();
    }

    private static String requireMethod(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(normalized)) {
            throw new LuminBusinessException(ErrorCode.D2001);
        }
        return normalized;
    }

    private static String requireOutcome(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!"VERIFIED".equals(normalized) && !"REJECTED".equals(normalized)) {
            throw new LuminBusinessException(ErrorCode.D2001);
        }
        return normalized;
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
