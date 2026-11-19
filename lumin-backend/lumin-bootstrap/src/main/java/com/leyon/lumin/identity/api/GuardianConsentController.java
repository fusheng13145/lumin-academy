package com.leyon.lumin.identity.api;

import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.foundation.web.IdempotentExecutor;
import com.leyon.lumin.foundation.web.IfMatch;
import com.leyon.lumin.identity.service.GuardianConsentService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 监护同意接入层（05 §3.1 list/create/get/revokeConsent；docs/13 UGC-003）：
 * 仅同意双方可读；创建要求监护人资格 + 受信关系回查；撤销要求 If-Match 与
 * Idempotency-Key，服务端不接受客户端自报监护关系。
 */
@RestController
public class GuardianConsentController {

    private final GuardianConsentService service;
    private final IdempotentExecutor idempotentExecutor;

    public GuardianConsentController(GuardianConsentService service,
                                     IdempotentExecutor idempotentExecutor) {
        this.service = service;
        this.idempotentExecutor = idempotentExecutor;
    }

    @GetMapping("/api/v1/guardian-consents")
    public ApiEnvelope list(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit) {
        return ApiEnvelope.ok(service.listVisible(subject, cursor, limit,
                LocalDateTime.now(ZoneOffset.UTC)));
    }

    @PostMapping("/api/v1/guardian-consents")
    public ResponseEntity<String> create(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String stubRole,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @RequestBody ConsentRequest request) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return idempotentExecutor.execute(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, null, request,
                () -> new IdempotentExecutor.ApiResult(200,
                        service.create(subject, stubRole, request.childId(),
                                request.proofRef(), request.scope(), now)));
    }

    @GetMapping("/api/v1/guardian-consents/{id}")
    public ResponseEntity<ApiEnvelope> get(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String id) {
        GuardianConsentService.ConsentView view = service.getVisible(subject, id);
        return ResponseEntity.ok()
                .eTag("\"" + view.revision() + "\"")
                .body(ApiEnvelope.ok(view));
    }

    @PostMapping("/api/v1/guardian-consents/{id}/revocations")
    public ResponseEntity<String> revoke(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String stubRole,
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            HttpServletRequest httpRequest,
            @RequestBody ReasonRequest request) {
        long expectedRevision = IfMatch.requireValid(ifMatch);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return idempotentExecutor.execute(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, ifMatch, request,
                () -> new IdempotentExecutor.ApiResult(200,
                        service.revoke(subject, stubRole, id, request.reason(),
                                expectedRevision, now)));
    }

    /** 契约 ConsentRequest。 */
    public record ConsentRequest(String childId, String proofRef, String scope) {
    }

    /** 契约 ReasonRequest。 */
    public record ReasonRequest(String reason) {
    }
}
