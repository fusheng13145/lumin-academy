package com.leyon.lumin.identity.api;

import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.identity.service.PasswordResetService;
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
 * 口令重置的**客服通道**接入层（V24）：运营可见待核验工单、可批准并拿到一次性重置码。
 *
 * <p>身份核验是线下流程（工单里只有登录名与时间，不含任何凭证），本层只负责
 * "谁有权签发码"：OPERATOR/ADMIN。重置码只在批准回执里出现一次，由客服口述给用户。
 */
@RestController
@RequestMapping("/api/v1/password-reset-tickets")
public class PasswordResetTicketController {

    private final PasswordResetService service;

    public PasswordResetTicketController(PasswordResetService service) {
        this.service = service;
    }

    /** 待人工核验工单（客服通道）。 */
    @GetMapping
    public ApiEnvelope list(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String role) {
        return ApiEnvelope.ok(service.listAwaiting(role));
    }

    /** 裁决：APPROVED 回一次性重置码（仅此一次）；REJECTED 关闭工单。 */
    @PostMapping("/{id}/decisions")
    public ApiEnvelope decide(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String operator,
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
        var issued = service.decide(operator, role, id, "APPROVED".equals(decision),
                LocalDateTime.now(ZoneOffset.UTC));
        // 重置码只在批准回执里出现这一次（服务端只留 SHA-256 摘要）
        return ApiEnvelope.ok(Map.of("id", issued.id(),
                "state", "APPROVED".equals(decision) ? "PENDING" : "REJECTED",
                "resetCode", issued.resetCode() == null ? "" : issued.resetCode(),
                "expiresAt", issued.expiresAt() == null ? "" : issued.expiresAt()));
    }

    /** 裁决请求体（APPROVED / REJECTED）。 */
    public record DecisionPayload(String decision) {
    }
}
