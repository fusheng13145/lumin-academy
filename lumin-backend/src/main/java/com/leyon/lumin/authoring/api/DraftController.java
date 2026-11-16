package com.leyon.lumin.authoring.api;


import tools.jackson.databind.ObjectMapper;
import com.leyon.lumin.authoring.api.dto.DraftCreateRequest;
import com.leyon.lumin.authoring.api.dto.DraftSaveRequest;
import com.leyon.lumin.authoring.api.dto.DraftView;
import com.leyon.lumin.authoring.service.DraftCommandService;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.idempotency.IdempotencyRules;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.idempotency.IdempotencyService;
import com.leyon.lumin.foundation.repository.IdempotencyRow;
import com.leyon.lumin.foundation.web.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.foundation.web.ReceiptView;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.Supplier;

/**
 * 草稿接入层（05 §3.2：createDraft / getDraft / saveDraft）。
 * 写操作要求 Idempotency-Key（05 §2）；保存要求 If-Match（05 §2 版本头）。
 * 主体来自认证过滤器注入的请求属性（服务端权威，非客户端自报）。
 */
@RestController
@RequestMapping("/api/v1/drafts")
public class DraftController {

    private final DraftCommandService draftCommandService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public DraftController(DraftCommandService draftCommandService,
                           IdempotencyService idempotencyService, ObjectMapper objectMapper) {
        this.draftCommandService = draftCommandService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<String> createDraft(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            jakarta.servlet.http.HttpServletRequest httpRequest,
            @RequestBody DraftCreateRequest request) {
        return withIdempotency(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, null, request,
                () -> draftCommandService.createDraft(subject, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiEnvelope> getDraft(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String id) {
        DraftView view = draftCommandService.getDraft(subject, id);
        return ResponseEntity.ok()
                .eTag("\"" + view.revision() + "\"")
                .body(ApiEnvelope.ok(view));
    }

    @PutMapping("/{id}")
    public ResponseEntity<String> saveDraft(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            jakarta.servlet.http.HttpServletRequest httpRequest,
            @RequestBody DraftSaveRequest request) {
        return withIdempotency(subject, "PUT", httpRequest.getRequestURI(),
                idempotencyKey, ifMatch, request,
                () -> draftCommandService.saveDraft(subject, id, ifMatch, request));
    }

    /**
     * 幂等包装（03 §4.1 第 3 条 / T09）：命中未过期同键同摘要 → 原样回放存储响应；
     * 同键不同载荷（含 If-Match 差异）→ B2003；新鲜执行成功后记录响应。
     */
    private ResponseEntity<String> withIdempotency(String subject, String method, String uri,
                                                   String idempotencyKey, String ifMatch,
                                                   Object payload, Supplier<ReceiptView> action) {
        if (IdempotencyRules.validateKey(idempotencyKey) != ErrorCode.OK) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        String digest = IdempotencyRules.requestDigest(
                objectMapper.writeValueAsString(payload) + "\n" + ifMatch);
        IdempotencyService.Scope scope = new IdempotencyService.Scope(subject, method,
                IdempotencyRules.normalizePath(uri), idempotencyKey, digest,
                LocalDateTime.now(ZoneOffset.UTC));
        var replay = idempotencyService.findReplay(scope);
        if (replay.isPresent()) {
            IdempotencyRow stored = replay.get();
            return ResponseEntity.status(stored.responseCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(stored.responseBody());
        }
        ReceiptView receipt = action.get();
        String body = objectMapper.writeValueAsString(ApiEnvelope.ok(receipt));
        idempotencyService.record(scope, 200, body);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
