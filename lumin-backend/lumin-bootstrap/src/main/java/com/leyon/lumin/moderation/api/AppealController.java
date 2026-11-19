package com.leyon.lumin.moderation.api;

import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.foundation.web.IdempotentExecutor;
import com.leyon.lumin.moderation.service.AppealQueryService;
import com.leyon.lumin.moderation.service.AppealService;
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
 * 申诉接入层（05 §3.3：createAppeal / getAppeal / reviewAppeal）。
 * 创建仅本人案件（服务层经 foundation 端口校验）；复核须独立复核人（原审回避）。
 */
@RestController
@RequestMapping("/api/v1")
public class AppealController {

    private final AppealService appealService;
    private final AppealQueryService appealQueryService;
    private final IdempotentExecutor idempotentExecutor;

    public AppealController(AppealService appealService,
                            AppealQueryService appealQueryService,
                            IdempotentExecutor idempotentExecutor) {
        this.appealService = appealService;
        this.appealQueryService = appealQueryService;
        this.idempotentExecutor = idempotentExecutor;
    }

    public record AppealCreateRequest(String caseId, String reason,
                                      List<String> evidenceRefs) {
    }

    /** 契约 AppealReviewRequest；兼容既有 uphold/decisionReason。 */
    public record AppealReviewRequest(String action, String reason, List<String> evidenceRefs,
                                      Boolean uphold, String decisionReason) {
    }

    /** 可见申诉列表（申请人/有效监护人；独立 L2/LEGAL 复核队列先过滤后分页）。 */
    @GetMapping("/appeals")
    public ApiEnvelope listAppeals(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String role,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiEnvelope.ok(appealQueryService.listVisible(subject, role, cursor, limit,
                LocalDateTime.now(ZoneOffset.UTC)));
    }

    @PostMapping("/appeals")
    public ResponseEntity<String> createAppeal(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @RequestBody AppealCreateRequest request) {
        return idempotentExecutor.execute(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, null, request,
                () -> new IdempotentExecutor.ApiResult(200, Map.of(
                        "id", appealService.createAppeal(subject, request.caseId(),
                                request.reason(), request.evidenceRefs()),
                        "state", "OPEN")));
    }

    @GetMapping("/appeals/{id}")
    public ResponseEntity<ApiEnvelope> getAppeal(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String role,
            @PathVariable String id) {
        var view = appealQueryService.getVisible(subject, role, id);
        return ResponseEntity.ok()
                .eTag("\"" + view.revision() + "\"")
                .body(ApiEnvelope.ok(view));
    }

    /** 复核（原审回避；UPHELD 维持 / OVERTURNED 改判，两个终态）。 */
    @PostMapping("/appeals/{id}/reviews")
    public ResponseEntity<String> reviewAppeal(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @RequestBody AppealReviewRequest request) {
        String action = request.action() != null ? request.action()
                : Boolean.TRUE.equals(request.uphold()) ? "UPHOLD" : "OVERTURN";
        String reason = request.reason() != null ? request.reason()
                : request.decisionReason();
        return idempotentExecutor.execute(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, null, request,
                () -> new IdempotentExecutor.ApiResult(200, Map.of(
                        "id", id,
                        "state", appealService.reviewAppeal(subject, id, action,
                                reason, request.evidenceRefs()))));
    }
}

