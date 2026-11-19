package com.leyon.lumin.governance.api;

import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.foundation.web.IdempotentExecutor;
import com.leyon.lumin.governance.service.RestrictionQueryService;
import com.leyon.lumin.governance.service.RestrictionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 治理措施接入层（05 §3.3：requestRestriction / reviewRestriction）。
 * 提案（PENDING）与独立复核（生效/驳回）分离；临时措施改判解除走同一复核链。
 */
@RestController
@RequestMapping("/api/v1/restrictions")
public class RestrictionController {

    private final RestrictionService restrictionService;
    private final RestrictionQueryService restrictionQueryService;
    private final IdempotentExecutor idempotentExecutor;

    public RestrictionController(RestrictionService restrictionService,
                                 RestrictionQueryService restrictionQueryService,
                                 IdempotentExecutor idempotentExecutor) {
        this.restrictionService = restrictionService;
        this.restrictionQueryService = restrictionQueryService;
        this.idempotentExecutor = idempotentExecutor;
    }

    public record RestrictionRequest(String workId, String chapterId, String caseId,
                                     String kind, String reason, String expiresAt) {
    }

    /** 可见限制列表（提案人/作品作者/授权作用域角色；先过滤后分页）。 */
    @GetMapping
    public ApiEnvelope list(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String role,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiEnvelope.ok(restrictionQueryService.listVisible(subject, role, cursor, limit,
                LocalDateTime.now(ZoneOffset.UTC)));
    }

    /** 限制详情（原始请求 + 完整审批记录；越权与不存在统一 404）。 */
    @GetMapping("/{id}")
    public ResponseEntity<ApiEnvelope> get(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String role,
            @PathVariable String id) {
        var view = restrictionQueryService.getVisible(subject, role, id,
                LocalDateTime.now(ZoneOffset.UTC));
        return ResponseEntity.ok()
                .eTag("\"" + view.revision() + "\"")
                .body(ApiEnvelope.ok(view));
    }

    @PostMapping
    public ResponseEntity<String> requestRestriction(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @RequestBody RestrictionRequest request) {
        return idempotentExecutor.execute(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, null, request,
                () -> new IdempotentExecutor.ApiResult(200, restrictionService.requestRestriction(
                        subject, request.workId(), request.chapterId(), request.kind(),
                        request.reason(), parseTime(request.expiresAt()), request.caseId())));
    }

    /** 契约 Review 请求体；兼容既有 activate 布尔。 */
    public record RestrictionReviewRequest(String action, String reason,
                                           List<String> evidenceRefs, Boolean activate) {
    }

    /** 独立复核：生效（PENDING→ACTIVE）/ 驳回（PENDING→REJECTED）；If-Match 乐观锁。 */
    @PostMapping("/{id}/reviews")
    public ResponseEntity<String> reviewRestriction(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @RequestBody RestrictionReviewRequest request) {
        boolean approve = request.action() == null
                ? Boolean.TRUE.equals(request.activate())
                : "APPROVE".equals(request.action());
        if (request.action() != null && !"APPROVE".equals(request.action())
                && !"REJECT".equals(request.action())) {
            throw new com.leyon.lumin.foundation.LuminBusinessException(
                    com.leyon.lumin.foundation.ErrorCodes.ErrorCode.P1001);
        }
        return idempotentExecutor.execute(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, ifMatch, request,
                () -> new IdempotentExecutor.ApiResult(200,
                        restrictionService.reviewRestriction(subject, id, approve,
                                request.reason(), request.evidenceRefs(), ifMatch)));
    }

    /** 临时措施改判解除（ACTIVE→REVOKED；终局/解约被状态机拒绝）。 */
    @PostMapping("/{id}/revocations")
    public ResponseEntity<String> revokeRestriction(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        return idempotentExecutor.execute(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, null, Map.of(),
                () -> new IdempotentExecutor.ApiResult(200,
                        restrictionService.revokeRestriction(subject, id)));
    }

    private static LocalDateTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value);
        } catch (java.time.format.DateTimeParseException e) {
            throw new com.leyon.lumin.foundation.LuminBusinessException(
                    com.leyon.lumin.foundation.ErrorCodes.ErrorCode.P1001);
        }
    }
}

