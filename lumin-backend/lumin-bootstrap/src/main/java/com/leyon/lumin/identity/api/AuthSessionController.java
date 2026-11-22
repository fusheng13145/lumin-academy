package com.leyon.lumin.identity.api;

import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.identity.service.AuthSessionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 会话签发/撤销接入层（docs/13 UGC-002 一阶段；05 §2）：
 * P0 签发端为有界替身集合（正式 OIDC/PKCE 随后续阶段替换签发端）；
 * 撤销走持有令牌自身（登出）；注销联动撤销全部会话（T36）。
 */
@RestController
@RequestMapping("/api/v1/auth/sessions")
public class AuthSessionController {

    private final AuthSessionService service;
    private final String authMode;

    public AuthSessionController(AuthSessionService service,
                                 @org.springframework.beans.factory.annotation.Value(
                                         "${lumin.auth.mode:demo}") String authMode) {
        this.service = service;
        this.authMode = authMode;
    }

    /** 签发会话（P0：替身名称 ∈ 有界集合；未知替身 401/A0001）。
     *
     * <p>2026-11-20 安全审查修复：替身签发**仅在 demo 模式可用**。此前
     * {@code lumin.auth.mode=session} 下该端点仍可签发任意替身会话（含 ADMIN），
     * 等于给生产留了一张免费管理员发卡机；现非 demo 模式一律 403/A2001，
     * 真实登录走 {@code POST /api/v1/auth/password-sessions}。
     */
    @PostMapping
    public ApiEnvelope issue(@RequestBody IssueRequest request) {
        if (!"demo".equals(authMode)) {
            throw new com.leyon.lumin.foundation.LuminBusinessException(
                    com.leyon.lumin.foundation.ErrorCodes.ErrorCode.A2001);
        }
        if (request == null || request.stub() == null || request.stub().isBlank()) {
            throw new com.leyon.lumin.foundation.LuminBusinessException(
                    com.leyon.lumin.foundation.ErrorCodes.ErrorCode.A0001);
        }
        var issued = service.issue(request.stub(), LocalDateTime.now(ZoneOffset.UTC));
        return ApiEnvelope.ok(Map.of(
                "token", issued.token(),
                "subject", issued.subject(),
                "stubName", issued.stubName(),
                "expiresAt", issued.expiresAt()));
    }

    /** 登出：撤销持有令牌对应会话（幂等；重复撤销同样 200）。 */
    @PostMapping("/revocations")
    public ApiEnvelope revoke(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        service.revoke(bearerOf(authorization));
        return ApiEnvelope.ok(Map.of("revoked", true));
    }

    /** 从 Authorization 头提取持有令牌（缺失时服务层按 null 幂等处理）。 */
    static String bearerOf(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        return authorization.substring("Bearer ".length()).trim();
    }

    /** 签发请求体（P0：有界替身名称）。 */
    public record IssueRequest(String stub) {
    }
}
