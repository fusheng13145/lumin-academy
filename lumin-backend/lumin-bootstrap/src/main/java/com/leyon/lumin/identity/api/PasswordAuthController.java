package com.leyon.lumin.identity.api;

import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.identity.service.PasswordCredentialService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 真实凭据接入层（2026-11-20 安全审查"必须先修"第 2/3 项）。
 *
 * <p>三个端点与既有会话令牌体系共用同一套持有令牌（不引入第二套认证）：
 * <ul>
 *   <li>{@code POST /api/v1/auth/registrations}：注册（豁免认证，登录名+口令+档案）</li>
 *   <li>{@code POST /api/v1/auth/password-sessions}：口令登录（豁免认证）</li>
 *   <li>{@code POST /api/v1/auth/password-changes}：改密（**必须已登录**，改后旧令牌全部失效）</li>
 * </ul>
 * 口令明文只在请求内存在，响应只回令牌；失败一律 401/A0001（不区分"账号不存在/口令错"），
 * 参数/策略不合格 400/P1001，登录名冲突 409/B2001。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class PasswordAuthController {

    private final PasswordCredentialService service;
    private final com.leyon.lumin.identity.service.AuthIpThrottleService throttle;

    public PasswordAuthController(PasswordCredentialService service,
                                  com.leyon.lumin.identity.service.AuthIpThrottleService throttle) {
        this.service = service;
        this.throttle = throttle;
    }

    /** 注册：建档 + 落 BCrypt 摘要 + 直接签发会话。 */
    @PostMapping("/registrations")
    public ApiEnvelope register(@RequestBody RegisterRequest request,
                               jakarta.servlet.http.HttpServletRequest httpRequest) {
        if (request == null) {
            throw new com.leyon.lumin.foundation.LuminBusinessException(
                    com.leyon.lumin.foundation.ErrorCodes.ErrorCode.P1001);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // 免认证入口的按 IP 配额先于任何建档动作（2026-11-20 审查 19 项）
        throttle.acquire(httpRequest.getRemoteAddr(), now);
        // 业务自然日按 Asia/Shanghai（与 ProfileController / BusinessTime 同源）
        LocalDate today = com.leyon.lumin.foundation.BusinessTime.localDate(
                java.time.Clock.system(com.leyon.lumin.foundation.BusinessTime.ASIA_SHANGHAI));
        var signed = service.register(request.loginId(), request.password(), request.nickname(),
                request.birthDate(), request.email(), today, now);
        return ApiEnvelope.ok(Map.of(
                "loginId", signed.loginId(),
                "subject", signed.subject(),
                "token", signed.token(),
                "expiresAt", signed.expiresAt()));
    }

    /** 口令登录：失败计数/锁定在库内权威；通过后按 MFA 状态决定直接签发还是发挑战。 */
    @PostMapping("/password-sessions")
    public ApiEnvelope login(@RequestBody LoginRequest request,
                             jakarta.servlet.http.HttpServletRequest httpRequest) {
        if (request == null) {
            throw new com.leyon.lumin.foundation.LuminBusinessException(
                    com.leyon.lumin.foundation.ErrorCodes.ErrorCode.P1001);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        // 先计数再校验：不给"响应时间差探测配额"留口子
        throttle.acquire(httpRequest.getRemoteAddr(), now);
        var outcome = service.authenticate(request.loginId(), request.password(), now);
        return switch (outcome.kind()) {
            case SESSION -> ApiEnvelope.ok(Map.of(
                    "loginId", outcome.loginId(),
                    "subject", outcome.subject(),
                    "token", outcome.token(),
                    "expiresAt", outcome.expiresAt(),
                    "mfaRequired", false));
            // 已启用二次验证：不回会话令牌，只回 5 分钟挑战令牌
            case MFA_REQUIRED -> ApiEnvelope.ok(Map.of(
                    "mfaRequired", true,
                    "challengeToken", outcome.token(),
                    "expiresAt", outcome.expiresAt()));
            // 特权账号未启用：只回受限令牌（仅可登记/确认二次验证）
            case MFA_ENROLLMENT_REQUIRED -> ApiEnvelope.ok(Map.of(
                    "mfaEnrollmentRequired", true,
                    "token", outcome.token(),
                    "expiresAt", outcome.expiresAt(),
                    "enrollPath", "/api/v1/auth/mfa/enrollments"));
        };
    }

    /** 改密：需持有有效令牌；成功后旧令牌立即失效，返回新令牌。 */
    @PostMapping("/password-changes")
    public ApiEnvelope changePassword(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestBody ChangePasswordRequest request) {
        if (request == null) {
            throw new com.leyon.lumin.foundation.LuminBusinessException(
                    com.leyon.lumin.foundation.ErrorCodes.ErrorCode.P1001);
        }
        var signed = service.changePassword(subject, request.currentPassword(),
                request.newPassword(), LocalDateTime.now(ZoneOffset.UTC));
        return ApiEnvelope.ok(Map.of(
                "loginId", signed.loginId(),
                "subject", signed.subject(),
                "token", signed.token(),
                "expiresAt", signed.expiresAt()));
    }

    /** 注册请求（birthDate 为 ISO 日期，交由 ProfileService 做年龄闸门；email 可选，用于口令找回）。 */
    public record RegisterRequest(String loginId, String password, String nickname,
                                  String birthDate, String email) {
    }

    /** 登录请求。 */
    public record LoginRequest(String loginId, String password) {
    }

    /** 改密请求。 */
    public record ChangePasswordRequest(String currentPassword, String newPassword) {
    }
}
