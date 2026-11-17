package com.leyon.lumin.audit.api;

import com.leyon.lumin.audit.repository.AuditRecordRow;
import com.leyon.lumin.audit.service.AuditQueryService;
import com.leyon.lumin.foundation.web.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;
import java.util.List;

/**
 * 审计读取接入层（05 §3.4 listAuditRecords：AUDITOR 授权作用域只读脱敏分页；
 * 无更新/删除/全文导出接口——append-only，T35 口径）。
 */
@RestController
@RequestMapping("/api/v1/audit-records")
public class AuditRecordController {

    private final AuditQueryService service;

    public AuditRecordController(AuditQueryService service) {
        this.service = service;
    }

    /** 审查读取（先过滤后 limit，时间倒序；仅 AUDITOR 作用域）。 */
    @GetMapping
    public ApiEnvelope list(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String stubRole,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String action,
            @RequestParam(defaultValue = "50") int limit) {
        List<AuditRecordRow> rows = service.search(stubRole, resourceType, resourceId,
                actorId, action, limit);
        return ApiEnvelope.ok(rows.stream().map(AuditRecordController::toView).toList());
    }

    /** 审查视图（脱敏快照原样返回；不含正文/令牌/证件）。 */
    public record AuditRecordView(String id, String actorId, String roleContext, String action,
                                  String resourceType, String resourceId, String beforeRedacted,
                                  String afterRedacted, String reason, String traceId,
                                  String eventAt, String digest) {
    }

    private static AuditRecordView toView(AuditRecordRow row) {
        return new AuditRecordView(row.id(), row.actorId(), row.roleContext(), row.action(),
                row.resourceType(), row.resourceId(), row.beforeRedacted(), row.afterRedacted(),
                row.reason(), row.traceId(),
                row.eventAt().toInstant(ZoneOffset.UTC).toString(), row.digest());
    }
}
