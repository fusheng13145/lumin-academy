package com.leyon.lumin.governance.api;

import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.foundation.web.IdempotentExecutor;
import com.leyon.lumin.governance.service.RestrictionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 治理措施接入层（05 §3.3：requestRestriction / reviewRestriction）。
 * 提案（PENDING）与独立复核（生效/驳回）分离；临时措施改判解除走同一复核链。
 */
@RestController
@RequestMapping("/api/v1/restrictions")
public class RestrictionController {

    private final RestrictionService restrictionService;
    private final IdempotentExecutor idempotentExecutor;

    public RestrictionController(RestrictionService restrictionService,
                                 IdempotentExecutor idempotentExecutor) {
        this.restrictionService = restrictionService;
        this.idempotentExecutor = idempotentExecutor;
    }

    public record RestrictionRequest(String workId, String chapterId, String kind,
                                     String reason) {
    }

    @PostMapping
    public ResponseEntity<String> requestRestriction(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @RequestBody RestrictionRequest request) {
        return idempotentExecutor.execute(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, null, request,
                () -> new IdempotentExecutor.ApiResult(200, Map.of(
                        "id", restrictionService.requestRestriction(subject,
                                request.workId(), request.chapterId(), request.kind(),
                                request.reason(), null),
                        "state", "PENDING")));
    }

    public record RestrictionReviewRequest(boolean activate) {
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
        return idempotentExecutor.execute(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, ifMatch, request,
                () -> new IdempotentExecutor.ApiResult(200, Map.of(
                        "id", id,
                        "state", restrictionService.reviewRestriction(subject, id,
                                request.activate(), ifMatch))));
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
                () -> new IdempotentExecutor.ApiResult(200, Map.of(
                        "id", id,
                        "state", restrictionService.revokeRestriction(subject, id))));
    }
}

