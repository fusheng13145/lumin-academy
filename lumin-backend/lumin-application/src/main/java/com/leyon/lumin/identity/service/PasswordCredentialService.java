package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.identity.domain.PasswordPolicy;
import com.leyon.lumin.identity.repository.PasswordCredentialMapper;
import com.leyon.lumin.identity.repository.PasswordCredentialRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 口令凭据服务（2026-11-20 安全审查"必须先修"第 2/3 项；05 §3.1 账号）。
 *
 * <p>真实凭据链路：注册（建档 + BCrypt 落库 + 签发会话）→ 登录（校验 + 失败计数/锁定
 * + 签发会话）→ 改密（校验旧口令 + 策略 + **撤销全部会话** + 换发新令牌）。
 *
 * <p>安全口径：
 * <ul>
 *   <li>哈希用 Spring Security Crypto 的 {@link BCryptPasswordEncoder}，**不自研**；
 *       口令明文只存在于请求内存，不落库、不打印；</li>
 *   <li>登录失败 5 次锁定 15 分钟，计数与锁定时间**落库**（多实例一致，重启不丢）；</li>
 *   <li>账号不存在与口令错误返回同一 401/A0001，且对不存在账号也执行一次 BCrypt
 *       比对，避免用响应时间枚举登录名；</li>
 *   <li>失败路径 {@code noRollbackFor = LuminBusinessException}：计数必须是已提交事实，
 *       否则抛错回滚会让暴力破解计数失效。</li>
 * </ul>
 */
@Service
public class PasswordCredentialService {

    private static final Logger log = LoggerFactory.getLogger(PasswordCredentialService.class);

    /** BCrypt 强度（10–12；12 为当前默认选择，单次校验约百毫秒级）。 */
    private static final int BCRYPT_STRENGTH = 12;
    private static final BCryptPasswordEncoder ENCODER =
            new BCryptPasswordEncoder(BCRYPT_STRENGTH);
    /** 时间均衡用的假摘要：账号不存在时也走一次比对。 */
    private static final String DUMMY_HASH = ENCODER.encode("lumin-timing-equalizer");

    /** 连续失败达到该值即锁定。 */
    private static final int MAX_FAILED = 5;
    /** 锁定时长。 */
    private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    /** 登录名：3–64 位，只允许小写字母/数字/点/下划线/连字符（存储前统一小写）。 */
    private static final Pattern LOGIN_ID_PATTERN =
            Pattern.compile("^[a-z0-9][a-z0-9._-]{2,63}$");
    /** 邮箱（V24 口令找回通道）：长度上限 254，单层 @ 与至少一个点分域名。 */
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]{1,64}@[^@\\s.]+(\\.[^@\\s.]+)+$");
    /** 无 role_assignment 时的基础角色。 */
    private static final String BASE_ROLE = "READER";

    private final PasswordCredentialMapper mapper;
    private final ProfileService profileService;
    private final AuthSessionService sessionService;
    private final MfaTotpService mfaService;
    private final com.leyon.lumin.identity.repository.RoleGrantMapper roleGrantMapper;

    public PasswordCredentialService(PasswordCredentialMapper mapper,
                                     ProfileService profileService,
                                     AuthSessionService sessionService,
                                     MfaTotpService mfaService,
                                     com.leyon.lumin.identity.repository.RoleGrantMapper
                                             roleGrantMapper) {
        this.mapper = mapper;
        this.profileService = profileService;
        this.sessionService = sessionService;
        this.mfaService = mfaService;
        this.roleGrantMapper = roleGrantMapper;
    }

    /** 注册结果（token 仅此一次返回；服务端只留摘要）。 */
    public record SignedIn(String loginId, String subject, String token, String expiresAt) {
    }

    /** 登录三种结局（2026-11-20 审查 05 项：MFA 与特权账号强制登记）。 */
    public enum LoginKind {
        /** 直接签发会话。 */
        SESSION,
        /** 已启用二次验证：返回挑战令牌，需用 /auth/mfa-challenges 兑换会话。 */
        MFA_REQUIRED,
        /** 特权账号（ADMIN）未启用二次验证：只发受限会话，仅可登记/确认 MFA。 */
        MFA_ENROLLMENT_REQUIRED
    }

    /** 登录结局（token 语义随 kind 变化）。 */
    public record LoginOutcome(LoginKind kind, String loginId, String subject, String token,
                               String expiresAt) {
    }

    /**
     * 特权角色集合（当前只对 ADMIN 强制二次验证；REVIEWER/OPERATOR 后续按风险分级放开）。
     * 角色来自 role_grant 权威记录，不接受客户端自报。
     */
    private boolean isPrivileged(String subject, LocalDateTime now) {
        return roleGrantMapper.findActiveBySubject(subject, now).stream()
                .anyMatch(grant -> "ADMIN".equals(grant.role()));
    }

    /**
     * 注册：校验登录名/邮箱/口令策略 → 建档（年龄闸门复用 ProfileService）→ 落摘要 → 签发会话。
     * 全流程同一事务：任一环节失败（含并发重复登录名/邮箱的唯一约束）不留半成品档案。
     *
     * @param rawEmail 可选邮箱（V24 口令找回邮件通道）；为空表示只能走客服通道找回
     */
    @Transactional
    public SignedIn register(String rawLoginId, String password, String nickname,
                             String birthDateText, String rawEmail,
                             LocalDate today, LocalDateTime now) {
        String loginId = normalizeLoginId(rawLoginId);
        if (loginId == null) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        String email = normalizeEmail(rawEmail);
        if (email == null && rawEmail != null && !rawEmail.isBlank()) {
            // 录入了但不合法 → 明确拒绝（静默丢弃会让用户以为可用邮件找回）
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        PasswordPolicy.Violation violation = PasswordPolicy.check(password, loginId);
        if (violation != null) {
            // 只记违规类型，绝不记口令明文
            log.warn("注册被拒 event=credential.register result=rejected reason={} loginId={}",
                    violation, loginId);
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        if (mapper.findByLoginId(loginId) != null) {
            log.warn("注册被拒 event=credential.register result=rejected reason=login_exists "
                    + "loginId={}", loginId);
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        String subject = UUID.randomUUID().toString();
        profileService.createProfile(subject, nickname, birthDateText, null, today, now);
        PasswordCredentialRow row = new PasswordCredentialRow(UUID.randomUUID().toString(),
                subject, loginId, ENCODER.encode(password), now, 0, null, null, "ACTIVE",
                now, now, subject, subject, 1L);
        try {
            mapper.insert(row, email);
        } catch (DuplicateKeyException e) {
            // 并发注册同一登录名/邮箱：唯一约束兜底，整体回滚（含刚建的档案）
            log.warn("注册被拒 event=credential.register result=conflict loginId={}", loginId);
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        AuthSessionService.IssuedSession session =
                sessionService.issueForPassword(subject, BASE_ROLE, now);
        log.info("注册成功 event=credential.register result=ok subject={} loginId={}",
                subject, loginId);
        return new SignedIn(loginId, subject, session.token(), session.expiresAt());
    }

    /** 登录：锁定优先 → 摘要比对 → 失败计数/锁定 → 成功清零 → MFA 裁决 → 签发。 */
    @Transactional(noRollbackFor = LuminBusinessException.class)
    public LoginOutcome authenticate(String rawLoginId, String password, LocalDateTime now) {
        String loginId = normalizeLoginId(rawLoginId);
        if (loginId == null || password == null || password.isEmpty()) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        PasswordCredentialRow row = mapper.findByLoginId(loginId);
        if (row == null) {
            // 账号不存在也做一次同强度比对（时间均衡，防登录名枚举）
            ENCODER.matches(password, DUMMY_HASH);
            log.warn("登录失败 event=auth.login result=rejected reason=unknown_login loginId={}",
                    loginId);
            throw new LuminBusinessException(ErrorCode.A0001);
        }
        if (!"ACTIVE".equals(row.state())) {
            log.warn("登录失败 event=auth.login result=rejected reason=disabled loginId={}",
                    loginId);
            throw new LuminBusinessException(ErrorCode.A0001);
        }
        if (row.lockedUntil() != null && now.isBefore(row.lockedUntil())) {
            log.warn("登录被拒 event=auth.login result=rejected reason=locked loginId={} "
                    + "lockedUntil={}", loginId, row.lockedUntil());
            throw new LuminBusinessException(ErrorCode.A0001);
        }
        if (!ENCODER.matches(password, row.passwordHash())) {
            int attempts = row.failedCount() + 1;
            LocalDateTime lockUntil = null;
            if (attempts >= MAX_FAILED) {
                lockUntil = now.plus(LOCK_DURATION);
                log.warn("账号锁定 event=auth.lock result=locked loginId={} attempts={} until={}",
                        loginId, attempts, lockUntil);
            }
            // 触发锁定时计数归零（锁定期满后重新给满配额）；失败计数必须提交
            // （noRollbackFor 保证不随 401 回滚）
            mapper.updateFailureState(row.id(), lockUntil == null ? attempts : 0, lockUntil, now);
            log.warn("登录失败 event=auth.login result=rejected reason=bad_password loginId={} "
                    + "attempts={} locked={}", loginId, attempts, lockUntil != null);
            throw new LuminBusinessException(ErrorCode.A0001);
        }
        mapper.resetSuccess(row.id(), now);
        // 口令通过后进入二次验证裁决（2026-11-20 审查 05 项）
        if (mfaService.isActive(row.subject())) {
            AuthSessionService.IssuedSession challenge =
                    sessionService.issueChallenge(row.subject(), now);
            log.info("登录待二次验证 event=auth.login result=mfa_required subject={} loginId={}",
                    row.subject(), loginId);
            return new LoginOutcome(LoginKind.MFA_REQUIRED, loginId, row.subject(),
                    challenge.token(), challenge.expiresAt());
        }
        if (isPrivileged(row.subject(), now)) {
            // 特权账号未启用二次验证：不给可用会话，只发"仅可登记 MFA"的受限会话
            AuthSessionService.IssuedSession restricted =
                    sessionService.issueEnrollmentRestricted(row.subject(), now);
            log.warn("特权账号强制登记二次验证 event=auth.login "
                    + "result=mfa_enrollment_required subject={} loginId={}",
                    row.subject(), loginId);
            return new LoginOutcome(LoginKind.MFA_ENROLLMENT_REQUIRED, loginId, row.subject(),
                    restricted.token(), restricted.expiresAt());
        }
        AuthSessionService.IssuedSession session =
                sessionService.issueForPassword(row.subject(), BASE_ROLE, now);
        log.info("登录成功 event=auth.login result=ok subject={} loginId={}",
                row.subject(), loginId);
        return new LoginOutcome(LoginKind.SESSION, loginId, row.subject(), session.token(),
                session.expiresAt());
    }

    /**
     * 改密：校验旧口令 → 策略 → 禁止复用旧口令 → 换摘要 → **撤销全部会话** → 换发新令牌。
     * 旧令牌立即失效（04 项要求），当前用户拿新令牌继续使用，不掉线。
     */
    @Transactional(noRollbackFor = LuminBusinessException.class)
    public SignedIn changePassword(String subject, String currentPassword, String newPassword,
                                   LocalDateTime now) {
        PasswordCredentialRow row = subject == null ? null : mapper.findBySubject(subject);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.A0001);
        }
        if (currentPassword == null || newPassword == null || currentPassword.isEmpty()
                || newPassword.isEmpty()) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        if (!ENCODER.matches(currentPassword, row.passwordHash())) {
            int attempts = row.failedCount() + 1;
            LocalDateTime lockUntil = attempts >= MAX_FAILED ? now.plus(LOCK_DURATION) : null;
            mapper.updateFailureState(row.id(), lockUntil == null ? attempts : 0, lockUntil, now);
            log.warn("改密被拒 event=password.change result=rejected reason=bad_current "
                    + "subject={} attempts={} locked={}", subject, attempts, lockUntil != null);
            throw new LuminBusinessException(ErrorCode.A0001);
        }
        PasswordPolicy.Violation violation = PasswordPolicy.check(newPassword, row.loginId());
        if (violation != null) {
            log.warn("改密被拒 event=password.change result=rejected reason={} subject={}",
                    violation, subject);
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        if (ENCODER.matches(newPassword, row.passwordHash())) {
            log.warn("改密被拒 event=password.change result=rejected reason=reuse subject={}",
                    subject);
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        mapper.updatePassword(row.id(), ENCODER.encode(newPassword), now);
        int revoked = sessionService.revokeAllFor(subject);
        AuthSessionService.IssuedSession session =
                sessionService.issueForPassword(subject, BASE_ROLE, now);
        log.info("改密成功 event=password.change result=ok subject={} revokedSessions={}",
                subject, revoked);
        return new SignedIn(row.loginId(), subject, session.token(), session.expiresAt());
    }

    /** 登录名规范化（空/格式非法返回 null → 调用方统一 400/P1001）。 */
    static String normalizeLoginId(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        return LOGIN_ID_PATTERN.matcher(trimmed).matches() ? trimmed : null;
    }

    /** 邮箱规范化（统一小写；为空返回 null；格式非法返回 null，由调用方区分"未填/非法"）。 */
    static String normalizeEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim().toLowerCase(Locale.ROOT);
        if (trimmed.length() > 254 || !EMAIL_PATTERN.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed;
    }

    /** 口令摘要（供重置链路复用同一强度；不自研、不自带盐策略）。 */
    static String hashPassword(String raw) {
        return ENCODER.encode(raw);
    }
}
