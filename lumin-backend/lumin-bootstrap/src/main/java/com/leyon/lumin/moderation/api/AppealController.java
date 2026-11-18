package com.leyon.lumin.moderation.api;

import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.foundation.web.IdempotentExecutor;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * 申诉接入层（05 §3.3：createAppeal / getAppeal / reviewAppeal）。
 * 创建仅本人案件（服务层经 foundation 端口校验）；复核须独立复核人（原审回避）。
 */
@RestController
@RequestMapping("/api/v1")
public class AppealController {

    private final AppealService appealService;
    private final IdempotentExecutor idempotentExecutor;

    public AppealController(AppealService appealService,
                            IdempotentExecutor idempotentExecutor) {
        this.appealService = appealService;
        this.idempotentExecutor = idempotentExecutor;
    }

    public record AppealCreateRequest(String caseId, String reason,
                                      java.util.List<String> evidenceRefs) {
    }

    public record AppealReviewRequest(boolean uphold, String decisionReason) {
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
    public ApiEnvelope getAppeal(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String id) {
        var appeal = appealService.getAppeal(subject, id);
        return ApiEnvelope.ok(appeal);
    }

    /** 复核（原审回避；UPHELD 维持 / OVERTURNED 改判，两个终态）。 */
    @PostMapping("/appeals/{id}/reviews")
    public ResponseEntity<String> reviewAppeal(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @RequestBody AppealReviewRequest request) {
        return idempotentExecutor.execute(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, null, request,
                () -> new IdempotentExecutor.ApiResult(200, Map.of(
                        "id", id,
                        "state", appealService.reviewAppeal(subject, id,
                                request.uphold(), request.decisionReason()))));
    }
}

