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

    /** 会话有效期（05 §2 登录态骨架：12 小时；工程执行候选）。 */
    private static final java.time.Duration SESSION_TTL = java.time.Duration.ofHours(12);

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
            throw new LuminBusinessException(ErrorCode.A0001);
        }
        String token = UUID.randomUUID() + "." + UUID.randomUUID();
        AuthSessionRow row = new AuthSessionRow(UUID.randomUUID().toString(),
                sha256(token), subject, stubName, StubIdentities.ROLES.get(stubName),
                now, now.plus(SESSION_TTL), null, now, now, "system", "system", 1L);
        mapper.insert(row);
        return new IssuedSession(token, subject, stubName,
                row.expiresAt().toInstant(ZoneOffset.UTC).toString());
    }

    /** 解析（存在 + 未撤销 + 未过期；否则 null → 401/A0001）。 */
    @Transactional(readOnly = true)
    public AuthSessionRow resolve(String token) {
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
        }
    }

    /** 注销联动：撤销该主体全部有效会话（T36 撤销会话钩子）。 */
    @Transactional
    public int revokeAllFor(String subject) {
        return mapper.revokeAllBySubject(subject, LocalDateTime.now(ZoneOffset.UTC));
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
