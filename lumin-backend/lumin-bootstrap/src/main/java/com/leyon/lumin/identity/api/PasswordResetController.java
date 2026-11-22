package com.leyon.lumin.identity.api;

import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.identity.service.PasswordResetService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 口令找回 / 重置接入层（V24）。
 *
 * <p>两个端点都免认证（用户此时可能根本登录不上）：
 * <ul>
 *   <li>{@code POST /api/v1/auth/password-resets}：请求重置（channel = EMAIL / CUSTOMER_SERVICE），
 *       **无论账号是否存在都返回同一 200**，不暴露账号是否存在；</li>
 *   <li>{@code POST /api/v1/auth/password-reset-confirmations}：用一次性重置码改密，
 *       成功后撤销全部会话，但**不发会话令牌**（避免绕过二次验证）。</li>
 * </ul>
 * 两个入口都吃按 IP 配额（{@code AuthIpThrottleService}），与登录/注册共用一个桶。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class PasswordResetController {

    private final PasswordResetService service;
    private final com.leyon.lumin.identity.service.AuthIpThrottleService throttle;

    public PasswordResetController(PasswordResetService service,
                                   com.leyon.lumin.identity.service.AuthIpThrottleService throttle) {
        this.service = service;
        this.throttle = throttle;
    }

    /** 请求重置（防枚举：存在与否同一响应）。 */
    @PostMapping("/password-resets")
    public ApiEnvelope request(@RequestBody(required = false) ResetRequest request,
                               jakarta.servlet.http.HttpServletRequest httpRequest) {
        if (request == null) {
            throw new com.leyon.lumin.foundation.LuminBusinessException(
                    com.leyon.lumin.foundation.ErrorCodes.ErrorCode.P1001);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        throttle.acquire(httpRequest.getRemoteAddr(), now);
        var result = service.request(request.loginId(), request.channel(), now);
        return ApiEnvelope.ok(Map.of("accepted", result.accepted(),
                "channel", result.channel()));
    }

    /** 用重置码改密（不发会话令牌：必须重新登录，二次验证照旧生效）。 */
    @PostMapping("/password-reset-confirmations")
    public ApiEnvelope confirm(@RequestBody(required = false) ConfirmRequest request,
                               jakarta.servlet.http.HttpServletRequest httpRequest) {
        if (request == null) {
            throw new com.leyon.lumin.foundation.LuminBusinessException(
                    com.leyon.lumin.foundation.ErrorCodes.ErrorCode.P1001);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        throttle.acquire(httpRequest.getRemoteAddr(), now);
        var result = service.confirm(request.loginId(), request.code(), request.newPassword(),
                now);
        return ApiEnvelope.ok(Map.of("loginId", result.loginId(), "reset", true));
    }

    /** 重置请求体（channel 缺省按 EMAIL 处理）。 */
    public record ResetRequest(String loginId, String channel) {
    }

    /** 确认请求体（10 位重置码，允许 5-5 分段）。 */
    public record ConfirmRequest(String loginId, String code, String newPassword) {
    }
}
