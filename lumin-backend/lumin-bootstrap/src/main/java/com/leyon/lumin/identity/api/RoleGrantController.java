package com.leyon.lumin.identity.api;

import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.identity.service.RoleGrantService;
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
 * 角色授予链接入层（docs/04 §5.2 role_grant；docs/13 UGC-004；05 §4.2）：
 * 提案 OPERATOR/ADMIN → 独立 ADMIN 审批（自批拒绝、互斥校验）→
 * APPROVED 即建 role_assignment 绑定；到期授予不产生有效角色。
 */
@RestController
@RequestMapping("/api/v1/role-grants")
public class RoleGrantController {

    private final RoleGrantService service;

    public RoleGrantController(RoleGrantService service) {
        this.service = service;
    }

    /** 提案（OPERATOR/ADMIN；作用域 GLOBAL/WORK/CASE，到期可选）。 */
    @PostMapping
    public ApiEnvelope propose(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String stubRole,
            @RequestBody ProposeRequest request) {
        String id = service.propose(subject, stubRole, request.targetUserId(),
                request.role(), request.scopeType(), request.scopeId(),
                request.expiresAt() == null ? null
                        : LocalDateTime.parse(request.expiresAt()),
                request.reason(), LocalDateTime.now(ZoneOffset.UTC));
        return ApiEnvelope.ok(Map.of("id", id, "state", "PENDING"));
    }

    /** 独立 ADMIN 审批（自批拒绝；互斥校验；APPROVED 即建绑定）。 */
    @PostMapping("/{id}/reviews")
    public ApiEnvelope review(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String stubRole,
            @PathVariable String id,
            @RequestBody ReviewRequest request) {
        String state = service.review(subject, stubRole, id,
                "APPROVE".equals(request.decision()),
                LocalDateTime.now(ZoneOffset.UTC));
        return ApiEnvelope.ok(Map.of("id", id, "state", state));
    }

    /** 提案请求体（role ∈ 契约 9 角色；expiresAt ISO-8601 可选）。 */
    public record ProposeRequest(String targetUserId, String role, String scopeType,
                                 String scopeId, String expiresAt, String reason) {
    }

    /** 审批请求体（APPROVE/REJECT）。 */
    public record ReviewRequest(String decision) {
    }
}
