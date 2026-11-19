package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.crypto.FieldCipher;
import com.leyon.lumin.foundation.values.AgeBand;
import com.leyon.lumin.foundation.values.RoleCode;
import com.leyon.lumin.identity.domain.AgePolicy;
import com.leyon.lumin.identity.repository.AgeVerificationMapper;
import com.leyon.lumin.identity.repository.AgeVerificationRow;
import com.leyon.lumin.identity.repository.RoleAssignmentMapper;
import com.leyon.lumin.identity.repository.UserProfileMapper;
import com.leyon.lumin.identity.repository.UserProfileRow;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 主体档案服务（identity 域所有权：02 §3.1）。authoring 经本服务读取
 * 生效年龄层与账号状态（允许边 authoring→identity）。
 *
 * <p>2026-11-19 增量三十六：接入 createProfile / getMe 两个 P0 契约操作。
 * 出生日期只以 {@link FieldCipher} 密文落库；未核验（无 age_verification 记录）
 * 时有效层按 AgePolicy 从严写 C4，自报成年不升级。C0（&lt;14）建档仍受
 * guardian_consent 真链路阻塞：在同意证据可核验前拒绝，不以 proofRef 冒充已同意。
 */
@Service
public class ProfileService {

    /** docs/04 §3：birth_date_cipher 的 AAD 用途绑定。 */
    private static final String BIRTH_DATE_AAD = "user_profile.birth_date";
    /** 骨架身份源标识；正式 OIDC 接入后由 issuer 参数替换。 */
    private static final String DEMO_ISSUER = "urn:lumin:demo";
    private static final int NICKNAME_MAX = 40;

    private final UserProfileMapper userProfileMapper;
    private final RoleAssignmentMapper roleAssignmentMapper;
    private final AgeVerificationMapper ageVerificationMapper;
    private final FieldCipher fieldCipher;

    public ProfileService(UserProfileMapper userProfileMapper,
                          RoleAssignmentMapper roleAssignmentMapper,
                          AgeVerificationMapper ageVerificationMapper,
                          FieldCipher fieldCipher) {
        this.userProfileMapper = userProfileMapper;
        this.roleAssignmentMapper = roleAssignmentMapper;
        this.ageVerificationMapper = ageVerificationMapper;
        this.fieldCipher = fieldCipher;
    }

    /** 跨模块可见的档案摘要（不泄漏 identity.repository 行类型）。 */
    public record ProfileSummary(String id, String effectiveAgeBand, String accountStatus) {
    }

    /** 建档回执（05 §3.1 Receipt：PROFILE / ACTIVE / revision）。 */
    public record ProfileReceipt(String id, String resourceType, String state, long revision) {
    }

    /** 本人档案视图（05 §3.1 Me）。 */
    public record MeView(String id, String nickname, String effectiveAgeBand,
                         List<String> roles, String accountStatus, long revision) {
    }

    /**
     * 首次建档（05 §3.1 createProfile）：昵称/生日校验、生日密文落库、
     * declared_age_band 由 AgePolicy 计算、effective_age_band 未核验从严 C4。
     *
     * <p>C0（&lt;14）需要有效 REGISTRATION 监护同意；本服务在 guardian_consent
     * 真链路接入前拒绝，避免“只收 proofRef 不核验”的伪同意。
     */
    @Transactional
    public ProfileReceipt createProfile(String subject, String nickname, String birthDateText,
                                        String registrationConsentProofRef, LocalDate today,
                                        LocalDateTime now) {
        if (subject == null || subject.isBlank()) {
            throw new LuminBusinessException(ErrorCode.A0001);
        }
        try {
            UUID.fromString(subject);
        } catch (IllegalArgumentException e) {
            // CHAR(36) 主键不接受非 UUID 主体；认证层已保证，此处兜底不落 500
            throw new LuminBusinessException(ErrorCode.A0001);
        }
        String normalizedNickname = normalizeNickname(nickname);
        LocalDate birthDate = parseBirthDate(birthDateText, today);
        if (registrationConsentProofRef != null
                && (registrationConsentProofRef.isBlank()
                || registrationConsentProofRef.length() > 200)) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        AgeBand declared = AgePolicy.ageBandAt(birthDate, today);
        if (declared == AgeBand.C0) {
            // 无 guardian_consent 权威记录时不得放行 <14 建档（05 §3.1）
            throw new LuminBusinessException(ErrorCode.A2002);
        }
        if (userProfileMapper.findById(subject) != null) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        byte[] birthDateCipher = fieldCipher.encrypt(birthDate.toString(), BIRTH_DATE_AAD);
        UserProfileRow row = new UserProfileRow(subject, DEMO_ISSUER, subject,
                normalizedNickname, birthDateCipher, declared.name(), null,
                AgeBand.C4.name(), null, "ACTIVE", 1L, null, now, now,
                subject, subject, 1L, null);
        try {
            userProfileMapper.insertProfile(row, now);
        } catch (DuplicateKeyException e) {
            // 并发首次建档：唯一约束兜底，统一 409 而非 500
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        return new ProfileReceipt(subject, "PROFILE", "ACTIVE", 1L);
    }

    /**
     * 本人档案（05 §3.1 getMe）：roles 以 role_assignment 有效绑定为权威；
     * 无绑定时回落到会话角色上下文（骨架身份源，正式 OIDC 后移除）。
     */
    @Transactional(readOnly = true)
    public MeView me(String subject, String baseRole, LocalDateTime now) {
        UserProfileRow row = subject == null ? null : userProfileMapper.findById(subject);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        Set<String> roles = new TreeSet<>();
        List<String> assigned = roleAssignmentMapper.findActiveRoleCodes(subject, now);
        if (!assigned.isEmpty()) {
            roles.addAll(assigned);
        } else if (baseRole != null && RoleCode.valueOfOrNull(baseRole) != null) {
            roles.add(baseRole);
        }
        // 核验到期即失效：已核验过但无当前有效记录时，读取口径从严回落 C4
        String effective = row.effectiveAgeBand();
        if (row.verifiedAgeBand() != null) {
            AgeVerificationRow valid = ageVerificationMapper.findLatestValid(subject, now);
            if (valid == null) {
                effective = AgeBand.C4.name();
            }
        }
        return new MeView(row.id(), row.nickname(), effective,
                List.copyOf(roles), row.accountStatus(),
                row.lockVersion() == null ? 1L : row.lockVersion());
    }

    /** 已认证主体档案缺失按 404/B2004 处理（createProfile 落地后仅匿名/注销路径可达）。 */
    public ProfileSummary requireProfile(String userId) {
        UserProfileRow row = userId == null ? null : userProfileMapper.findById(userId);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        return new ProfileSummary(row.id(), row.effectiveAgeBand(), row.accountStatus());
    }

    /** 批量档案读取（审核队列年龄带组装用；缺失主体不出现在 Map 中，由调用方判缺失）。 */
    public java.util.Map<String, ProfileSummary> findProfiles(List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return java.util.Map.of();
        }
        return userProfileMapper.findByIds(userIds.stream().filter(java.util.Objects::nonNull)
                        .distinct().toList()).stream()
                .collect(java.util.stream.Collectors.toMap(UserProfileRow::id,
                        row -> new ProfileSummary(row.id(), row.effectiveAgeBand(),
                                row.accountStatus())));
    }

    /** 读者书城展示用作者昵称（PUBLIC 级字段；缺失返回 null）。 */
    public String findNickname(String userId) {
        UserProfileRow row = userId == null ? null : userProfileMapper.findById(userId);
        return row == null ? null : row.nickname();
    }

    private static String normalizeNickname(String nickname) {
        if (nickname == null) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        String normalized = nickname.trim();
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints < 1 || codePoints > NICKNAME_MAX) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        return normalized;
    }

    private static LocalDate parseBirthDate(String birthDateText, LocalDate today) {
        if (birthDateText == null || birthDateText.isBlank()) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        LocalDate birthDate;
        try {
            birthDate = LocalDate.parse(birthDateText);
        } catch (DateTimeParseException e) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        if (birthDate.isAfter(today)) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        return birthDate;
    }
}
