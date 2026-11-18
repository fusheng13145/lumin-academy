package com.leyon.lumin.configuration.api;

import com.leyon.lumin.configuration.repository.ConfigRevisionRow;
import com.leyon.lumin.configuration.service.ConfigRevisionService;
import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 配置修订接入层（05 §3.4 config-revisions：提案/审批/激活/审查列表）。
 * 提案 OPERATOR/REVIEWER_L2；审批独立 ADMIN（法务确认随请求体）；
 * 激活 ADMIN 仅触发已批准计划（无请求体覆盖，T30）；payload 批后冻结。
 */
@RestController
@RequestMapping("/api/v1/config-revisions")
public class ConfigRevisionController {

    private final ConfigRevisionService service;

    public ConfigRevisionController(ConfigRevisionService service) {
        this.service = service;
    }

    /** 审查列表（先过滤后 limit；仅配置授权角色域）。 */
    @GetMapping
    public ApiEnvelope list(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String stubRole,
            @RequestParam(required = false) String configKey,
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "20") int limit) {
        List<ConfigRevisionRow> rows = service.list(stubRole, configKey, state, limit);
        return ApiEnvelope.ok(rows.stream().map(ConfigRevisionController::toView).toList());
    }

    /** 单条审查读取（原始提案 payload、审批身份、回滚留痕）。 */
    @GetMapping("/{id}")
    public ApiEnvelope get(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String stubRole,
            @PathVariable String id) {
        return ApiEnvelope.ok(toView(service.get(stubRole, id)));
    }

    /** 类型化提案（初建即 IN_REVIEW；最低合规规则不能改弱，T31）。 */
    @PostMapping
    public ApiEnvelope propose(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String stubRole,
            @RequestBody ProposeRequest request) {
        String id = service.propose(subject, stubRole, request.configKey(), request.payload(),
                request.reason(), parseTime(request.effectiveAt()), request.rolloutBasisPoints(),
                request.rollbackOf());
        return ApiEnvelope.ok(java.util.Map.of("id", id, "state", "IN_REVIEW"));
    }

    /** 独立 ADMIN 审批（申请人不得自批；REVIEW_ROUTING/ACCESS_POLICY 批准须法务确认）。 */
    @PostMapping("/{id}/reviews")
    public ApiEnvelope review(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String stubRole,
            @PathVariable String id,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody ReviewRequest request) {
        String state = service.review(subject, stubRole, id, ifMatch,
                "APPROVE".equals(request.decision()), request.reason(),
                request.legalConfirmation() != null && request.legalConfirmation());
        return ApiEnvelope.ok(java.util.Map.of("id", id, "state", state));
    }

    /** 激活（ADMIN；到生效时间才可，提前 409；无请求体覆盖，T30）。 */
    @PostMapping("/{id}/activations")
    public ApiEnvelope activate(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String stubRole,
            @PathVariable String id) {
        String state = service.activate(subject, stubRole, id);
        return ApiEnvelope.ok(java.util.Map.of("id", id, "state", state));
    }

    private static LocalDateTime parseTime(String value) {
        if (value == null) {
            return LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1);
        }
        return LocalDateTime.parse(value.replace("Z", ""));
    }

    /** 审查视图（含原始 payload、审批身份与乐观锁版本——审批 If-Match 依据）。 */
    public record ConfigRevisionView(String id, String configKey, Long revision,
                                     Long baseRevision, String state, String payload,
                                     String reason, String requestedBy, String approvedBy,
                                     String effectiveAt, Integer rolloutBasisPoints,
                                     String rollbackOf, Long lockVersion) {
    }

    private static ConfigRevisionView toView(ConfigRevisionRow row) {
        return new ConfigRevisionView(row.id(), row.configKey(), row.revision(),
                row.baseRevision(), row.state(), row.payload(), row.reason(),
                row.requestedBy(), row.approvedBy(),
                row.effectiveAt().toInstant(ZoneOffset.UTC).toString(),
                row.rolloutBasisPoints(), row.rollbackOf(), row.lockVersion());
    }

    /** 提案请求体（类型化 payload 以 JSON 字符串携带；回滚走 rollbackOf）。 */
    public record ProposeRequest(String configKey, String payload, String reason,
                                 String effectiveAt, Integer rolloutBasisPoints,
                                 String rollbackOf) {
    }

    /** 审批请求体（05：APPROVE/REJECT + reason；法务确认随请求）。 */
    public record ReviewRequest(String decision, String reason, Boolean legalConfirmation) {
    }
}

