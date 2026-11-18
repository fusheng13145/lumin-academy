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

    public AuthSessionController(AuthSessionService service) {
        this.service = service;
    }

    /** 签发会话（P0：替身名称 ∈ 有界集合；未知替身 401/A0001）。 */
    @PostMapping
    public ApiEnvelope issue(@RequestBody IssueRequest request) {
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
