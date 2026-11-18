package com.leyon.lumin.authoring.api;

import com.leyon.lumin.authoring.api.dto.DraftCreateRequest;
import com.leyon.lumin.authoring.api.dto.DraftSaveRequest;
import com.leyon.lumin.authoring.api.dto.DraftView;
import com.leyon.lumin.authoring.service.DraftCommandService;
import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.foundation.web.IdempotentExecutor;
import jakarta.servlet.http.HttpServletRequest;
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


/**
 * 草稿接入层（05 §3.2：createDraft / getDraft / saveDraft / submitDraft）。
 * 写操作要求 Idempotency-Key（05 §2）；保存与提交要求 If-Match；
 * 主体来自认证过滤器注入的请求属性（服务端权威，非客户端自报）。
 */
@RestController
@RequestMapping("/api/v1/drafts")
public class DraftController {

    private final DraftCommandService draftCommandService;
    private final IdempotentExecutor idempotentExecutor;

    public DraftController(DraftCommandService draftCommandService,
                           IdempotentExecutor idempotentExecutor) {
        this.draftCommandService = draftCommandService;
        this.idempotentExecutor = idempotentExecutor;
    }

    @PostMapping
    public ResponseEntity<String> createDraft(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @RequestBody DraftCreateRequest request) {
        return idempotentExecutor.execute(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, null, request,
                () -> new IdempotentExecutor.ApiResult(200,
                        draftCommandService.createDraft(subject, request)));
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
            HttpServletRequest httpRequest,
            @RequestBody DraftSaveRequest request) {
        return idempotentExecutor.execute(subject, "PUT", httpRequest.getRequestURI(),
                idempotencyKey, ifMatch, request,
                () -> new IdempotentExecutor.ApiResult(200,
                        draftCommandService.saveDraft(subject, id, ifMatch, request)));
    }

    /** 提交端点已按域所有权移至 publication 接入层（URL 不变）：publication/api/DraftSubmissionController。 */
}

