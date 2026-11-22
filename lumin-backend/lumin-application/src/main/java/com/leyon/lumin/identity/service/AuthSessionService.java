package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.identity.domain.StubIdentities;
import com.leyon.lumin.identity.repository.AuthSessionMapper;
import com.leyon.lumin.identity.repository.AuthSessionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 服务端权威会话（docs/13 UGC-002 一阶段；05 §2）：
 * 签发不透明持有令牌（双 UUID，原始令牌不落库——仅存 SHA-256 摘要），
 * 解析校验存在/撤销/过期；注销联动撤销全部会话。
 * OIDC/PKCE 为签发端的后续替换（UGC-002 后续阶段），校验/撤销语义不变。
 */
@Service
public class AuthSessionService {

    /** 会话生命周期运行日志（2026-11-20 安全审查：登录/登出此前无任何业务日志）。 */
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AuthSessionService.class);

    /** 会话有效期（05 §2 登录态骨架：12 小时；工程执行候选）。 */
    private static final java.time.Duration SESSION_TTL = java.time.Duration.ofHours(12);
    /** 二次验证挑战令牌有效期（2026-11-20 审查 05 项：MFA 登录第一步）。 */
    private static final java.time.Duration CHALLENGE_TTL = java.time.Duration.ofMinutes(5);
    /** 特权账号"仅允许登记二次验证"的受限会话有效期。 */
    private static final java.time.Duration ENROLLMENT_TTL = java.time.Duration.ofMinutes(30);
    /** 挑战令牌标记（stub_name 列；此类令牌**不能**当会话用，见 resolve）。 */
    public static final String STUB_MFA_CHALLENGE = "MFA_CHALLENGE";
    /** 受限会话标记（只允许访问 /api/v1/auth/mfa/** 与登出）。 */
    public static final String STUB_MFA_ENROLL_ONLY = "MFA_ENROLL_ONLY";

    private final AuthSessionMapper mapper;

    public AuthSessionService(AuthSessionMapper mapper) {
        this.mapper = mapper;
    }

    /** 签发（仅有界替身集合内成员；P0 无真实身份源——如实登记）。 */
    @Transactional
    public IssuedSession issue(String stubName, LocalDateTime now) {
        String subject = StubIdentities.SUBJECTS.get(stubName);
        if (subject == null) {
            // 未知替身：不接受任意自报身份
            // 只记替身名与结果，不记令牌（令牌此时尚未生成）
            log.warn("会话签发被拒 event=session.issue result=rejected reason=unknown_stub stub={}",
                    stubName);
            throw new LuminBusinessException(ErrorCode.A0001);
        }
        String token = UUID.randomUUID() + "." + UUID.randomUUID();
        AuthSessionRow row = new AuthSessionRow(UUID.randomUUID().toString(),
                sha256(token), subject, stubName, StubIdentities.ROLES.get(stubName),
                now, now.plus(SESSION_TTL), null, now, now, "system", "system", 1L);
        mapper.insert(row);
        log.info("会话签发 event=session.issue result=ok subject={} stub={} sessionId={} expiresAt={}",
                subject, stubName, row.id(), row.expiresAt());
        return new IssuedSession(token, subject, stubName,
                row.expiresAt().toInstant(ZoneOffset.UTC).toString());
    }

    /** 口令登录签发（2026-11-20 安全审查 04/05 项）。
     *
     * <p>与替身签发的差别只在"凭什么签"：真实凭据由 {@code PasswordCredentialService}
     * 校验通过后调用本方法；令牌本身仍是不可猜、只存摘要、可撤销的持有令牌，
     * 解析/撤销语义与替身签发完全一致（不引入第二套令牌体系）。
     *
     * @param subject   凭据持有人主体
     * @param baseRole  基础角色（无 role_assignment 时生效，如 READER）
     * @param now       业务时钟
     */
    @Transactional
    public IssuedSession issueForPassword(String subject, String baseRole, LocalDateTime now) {
        String token = UUID.randomUUID() + "." + UUID.randomUUID();
        AuthSessionRow row = new AuthSessionRow(UUID.randomUUID().toString(),
                sha256(token), subject, "PASSWORD_LOGIN", baseRole,
                now, now.plus(SESSION_TTL), null, now, now, subject, subject, 1L);
        mapper.insert(row);
        log.info("会话签发 event=session.issue result=ok issuer=password subject={} sessionId={} "
                + "expiresAt={}", subject, row.id(), row.expiresAt());
        return new IssuedSession(token, subject, "PASSWORD_LOGIN",
                row.expiresAt().toInstant(ZoneOffset.UTC).toString());
    }

    /** 二次验证挑战令牌（5 分钟、单次、只能用于 /auth/mfa-challenges 兑换会话）。 */
    @Transactional
    public IssuedSession issueChallenge(String subject, LocalDateTime now) {
        String token = UUID.randomUUID() + "." + UUID.randomUUID();
        AuthSessionRow row = new AuthSessionRow(UUID.randomUUID().toString(), sha256(token),
                subject, STUB_MFA_CHALLENGE, "MFA", now, now.plus(CHALLENGE_TTL), null,
                now, now, subject, subject, 1L);
        mapper.insert(row);
        log.info("二次验证挑战签发 event=mfa.challenge result=ok subject={} sessionId={} "
                + "expiresAt={}", subject, row.id(), row.expiresAt());
        return new IssuedSession(token, subject, STUB_MFA_CHALLENGE,
                row.expiresAt().toInstant(ZoneOffset.UTC).toString());
    }

    /** 特权账号未启用 MFA 时的受限会话：只可用于登记/确认二次验证与登出。 */
    @Transactional
    public IssuedSession issueEnrollmentRestricted(String subject, LocalDateTime now) {
        String token = UUID.randomUUID() + "." + UUID.randomUUID();
        AuthSessionRow row = new AuthSessionRow(UUID.randomUUID().toString(), sha256(token),
                subject, STUB_MFA_ENROLL_ONLY, "MFA", now, now.plus(ENROLLMENT_TTL), null,
                now, now, subject, subject, 1L);
        mapper.insert(row);
        log.warn("特权账号受限会话签发 event=session.issue result=restricted "
                + "reason=mfa_required subject={} sessionId={}", subject, row.id());
        return new IssuedSession(token, subject, STUB_MFA_ENROLL_ONLY,
                row.expiresAt().toInstant(ZoneOffset.UTC).toString());
    }

    /** 解析会话令牌（存在 + 未撤销 + 未过期 + **不是挑战/受限标记**；否则 null）。 */
    @Transactional(readOnly = true)
    public AuthSessionRow resolve(String token) {
        AuthSessionRow row = resolveRaw(token);
        if (row == null || STUB_MFA_CHALLENGE.equals(row.stubName())) {
            // 挑战令牌在结构上不能当会话用（即便过滤器被误改也不会放行）
            return null;
        }
        return row;
    }

    /** 解析二次验证挑战令牌（stub_name 必须是挑战标记；否则 null）。 */
    @Transactional(readOnly = true)
    public AuthSessionRow resolveChallenge(String token) {
        AuthSessionRow row = resolveRaw(token);
        if (row == null || !STUB_MFA_CHALLENGE.equals(row.stubName())) {
            return null;
        }
        return row;
    }

    private AuthSessionRow resolveRaw(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        AuthSessionRow row = mapper.findByTokenHash(sha256(token));
        if (row == null || row.revokedAt() != null
                || row.expiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            return null;
        }
        return row;
    }

    /** 登出：撤销持有令牌对应会话（幂等）。 */
    @Transactional
    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            mapper.revoke(sha256(token), LocalDateTime.now(ZoneOffset.UTC), "system");
            // 不记令牌原文/摘要，只记"已处理登出"
            log.info("会话撤销 event=session.revoke result=ok");
        } else {
            log.info("会话撤销 event=session.revoke result=noop reason=missing_token");
        }
    }

    /** 注销联动：撤销该主体全部有效会话（T36 撤销会话钩子）。 */
    @Transactional
    public int revokeAllFor(String subject) {
        int revoked = mapper.revokeAllBySubject(subject, LocalDateTime.now(ZoneOffset.UTC));
        log.info("会话批量撤销 event=session.revoke_all subject={} revoked={}", subject, revoked);
        return revoked;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }

    /** 签发结果（token 仅此一次返回，服务端不留原文）。 */
    public record IssuedSession(String token, String subject, String stubName,
                                String expiresAt) {
    }
}
