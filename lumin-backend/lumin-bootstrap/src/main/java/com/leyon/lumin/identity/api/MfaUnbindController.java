package com.leyon.lumin.identity.api;

import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.identity.service.MfaUnbindService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 二次验证运维解绑接入层（V23）。
 *
 * <p>刻意放在 {@code /api/v1/mfa-unbind-requests} 而不是 {@code /auth/mfa/**}：
 * 后者的前缀对"仅可登记"的受限会话是放行的，运营端点不应享受那条放宽。
 *
 * <p>三个端点：申请人提交工单（需登录）、运营列待办、运营裁决（OPERATOR/ADMIN）。
 */
@RestController
@RequestMapping("/api/v1/mfa-unbind-requests")
public class MfaUnbindController {

    private final MfaUnbindService service;

    public MfaUnbindController(MfaUnbindService service) {
        this.service = service;
    }

    /** 提交解绑工单（必须已启用二次验证；同一时间只能有一张）。 */
    @PostMapping
    public ApiEnvelope request(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestBody(required = false) RequestBodyPayload payload) {
        String reason = payload == null ? null : payload.reason();
        return ApiEnvelope.ok(service.request(subject, reason, LocalDateTime.now(ZoneOffset.UTC)));
    }

    /** 待处理工单（OPERATOR/ADMIN）。 */
    @GetMapping
    public ApiEnvelope list(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String role) {
        return ApiEnvelope.ok(service.listPending(role));
    }

    /** 裁决（OPERATOR/ADMIN）：APPROVED 即种子作废 + 恢复码清空 + 全量撤销会话。 */
    @PostMapping("/{id}/decisions")
    public ApiEnvelope decide(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String reviewer,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String role,
            @PathVariable String id,
            @RequestBody DecisionPayload payload) {
        if (payload == null || payload.decision() == null) {
            throw new com.leyon.lumin.foundation.LuminBusinessException(
                    com.leyon.lumin.foundation.ErrorCodes.ErrorCode.P1001);
        }
        String decision = payload.decision().trim().toUpperCase(java.util.Locale.ROOT);
        if (!"APPROVED".equals(decision) && !"REJECTED".equals(decision)) {
            throw new com.leyon.lumin.foundation.LuminBusinessException(
                    com.leyon.lumin.foundation.ErrorCodes.ErrorCode.P1001);
        }
        var result = service.decide(reviewer, role, id, "APPROVED".equals(decision),
                LocalDateTime.now(ZoneOffset.UTC));
        return ApiEnvelope.ok(Map.of("id", result.id(), "state", result.state(),
                "revokedSessions", result.revokedSessions()));
    }

    /** 申请请求体（理由必填，服务层校验长度）。 */
    public record RequestBodyPayload(String reason) {
    }

    /** 裁决请求体（APPROVED / REJECTED）。 */
    public record DecisionPayload(String decision) {
    }
}
