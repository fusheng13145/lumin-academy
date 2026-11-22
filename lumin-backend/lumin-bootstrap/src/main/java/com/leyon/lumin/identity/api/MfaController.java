package com.leyon.lumin.identity.api;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.identity.repository.AuthSessionRow;
import com.leyon.lumin.identity.service.AuthSessionService;
import com.leyon.lumin.identity.service.MfaTotpService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 二次验证（TOTP）接入层（2026-11-20 安全审查 05 项）。
 *
 * <p>三个端点：
 * <ul>
 *   <li>{@code POST /api/v1/auth/mfa/enrollments}：登记（需登录；特权账号用受限令牌亦可），
 *       返回 {@code secret} 与 {@code otpauthUri}，状态 PENDING；</li>
 *   <li>{@code POST /api/v1/auth/mfa/confirmations}：确认（校验一次验证码 → ACTIVE），
 *       下发一次性恢复码，并**换发新会话**（撤离受限会话）；</li>
 *   <li>{@code POST /api/v1/auth/mfa-challenges}：登录第二步（豁免认证，凭 5 分钟挑战令牌 +
 *       验证码/恢复码兑换正式会话）。</li>
 * </ul>
 * 挑战令牌只能用于第三个端点：{@code AuthSessionService.resolve} 会把它判为"非会话"，
 * 即使过滤器被改动也不会被当作登录态使用。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class MfaController {

    private final MfaTotpService mfaService;
    private final AuthSessionService sessionService;
    private final com.leyon.lumin.identity.service.AuthIpThrottleService throttle;

    public MfaController(MfaTotpService mfaService, AuthSessionService sessionService,
                         com.leyon.lumin.identity.service.AuthIpThrottleService throttle) {
        this.mfaService = mfaService;
        this.sessionService = sessionService;
        this.throttle = throttle;
    }

    /** 登记二次验证（生成种子；已 ACTIVE 则 409）。 */
    @PostMapping("/mfa/enrollments")
    public ApiEnvelope enroll(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestBody(required = false) EnrollRequest request) {
        String label = request == null || request.accountLabel() == null ? "user"
                : request.accountLabel();
        var enrollment = mfaService.enroll(subject, label, LocalDateTime.now(ZoneOffset.UTC));
        return ApiEnvelope.ok(Map.of(
                "secret", enrollment.secretBase32(),
                "otpauthUri", enrollment.otpauthUri(),
                "status", enrollment.status()));
    }

    /** 确认二次验证：成功即 ACTIVE，下发恢复码并换发正式会话。 */
    @PostMapping("/mfa/confirmations")
    public ApiEnvelope confirm(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestBody ConfirmRequest request) {
        if (request == null || request.code() == null) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        var confirmation = mfaService.confirm(subject, request.code(), now);
        // 撤离全部旧会话（含"仅可登记"的受限会话），换发一枚正式会话
        int revoked = sessionService.revokeAllFor(subject);
        var session = sessionService.issueForPassword(subject, "READER", now);
        return ApiEnvelope.ok(Map.of(
                "status", "ACTIVE",
                "recoveryCodes", confirmation.recoveryCodes(),
                "token", session.token(),
                "expiresAt", session.expiresAt(),
                "revokedSessions", revoked));
    }

    /** 登录第二步：挑战令牌 + 验证码/恢复码 → 正式会话（挑战令牌一次性消费）。 */
    @PostMapping("/mfa-challenges")
    public ApiEnvelope completeChallenge(@RequestBody ChallengeRequest request,
                                         jakarta.servlet.http.HttpServletRequest httpRequest) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // 挑战端点是免认证入口：验证码可被无限次穷举，必须先吃按 IP 配额
        throttle.acquire(httpRequest.getRemoteAddr(), now);
        if (request == null || request.challengeToken() == null || request.code() == null) {
            throw new LuminBusinessException(ErrorCode.A0001);
        }
        AuthSessionRow challenge = sessionService.resolveChallenge(request.challengeToken());
        if (challenge == null) {
            throw new LuminBusinessException(ErrorCode.A0001);
        }
        if (!mfaService.verify(challenge.subject(), request.code(), now)) {
            throw new LuminBusinessException(ErrorCode.A0001);
        }
        // 挑战令牌一次性：兑换后立即撤销
        sessionService.revoke(request.challengeToken());
        var session = sessionService.issueForPassword(challenge.subject(), "READER", now);
        return ApiEnvelope.ok(Map.of(
                "subject", session.subject(),
                "token", session.token(),
                "expiresAt", session.expiresAt()));
    }

    /** 登记请求（accountLabel 仅用于 otpauth 展示，可为空）。 */
    public record EnrollRequest(String accountLabel) {
    }

    /** 确认请求（6 位验证码）。 */
    public record ConfirmRequest(String code) {
    }

    /** 登录第二步请求（挑战令牌 + 验证码或恢复码）。 */
    public record ChallengeRequest(String challengeToken, String code) {
    }
}
