package com.leyon.lumin.moderation.api;

import com.leyon.lumin.foundation.web.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.foundation.web.IdempotentExecutor;
import com.leyon.lumin.moderation.api.dto.CaseView;
import com.leyon.lumin.moderation.api.dto.DecisionRequest;
import com.leyon.lumin.moderation.service.ReviewCaseService;
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

import java.util.List;
import java.util.Map;

/**
 * 审核案件接入层（05 §3.3：listCases / claimCase / decideCase）。
 * 审核员身份经替身集合（X-Lumin-Demo-Subject：REVIEWER_L1A / REVIEWER_L2A）；
 * 领取/裁决均要求 If-Match + Idempotency-Key（05 §2）；队列列表不含正文。
 */
@RestController
@RequestMapping("/api/v1/review-cases")
public class ReviewCaseController {

    private final ReviewCaseService reviewCaseService;
    private final IdempotentExecutor idempotentExecutor;

    public ReviewCaseController(ReviewCaseService reviewCaseService,
                                IdempotentExecutor idempotentExecutor) {
        this.reviewCaseService = reviewCaseService;
        this.idempotentExecutor = idempotentExecutor;
    }

    @GetMapping
    public ApiEnvelope list() {
        List<CaseView> cases = reviewCaseService.listQueued();
        return ApiEnvelope.ok(cases);
    }

    /** 领取（If-Match 原子领取 + 15 分钟租约，03 §2.4）。 */
    @PostMapping("/{id}/claims")
    public ResponseEntity<String> claim(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String reviewerRole,
            @PathVariable String id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        return idempotentExecutor.execute(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, ifMatch, Map.of(),
                () -> new IdempotentExecutor.ApiResult(200,
                        reviewCaseService.claim(subject, reviewerRole, id, ifMatch)));
    }

    /** 裁决（grade 配对；HIGH 双审；治理语义登记）。 */
    @PostMapping("/{id}/decisions")
    public ResponseEntity<String> decide(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String reviewerRole,
            @PathVariable String id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @RequestBody DecisionRequest request) {
        return idempotentExecutor.execute(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, ifMatch, request,
                () -> new IdempotentExecutor.ApiResult(200,
                        reviewCaseService.decide(subject, reviewerRole, id, ifMatch, request)));
    }
}
