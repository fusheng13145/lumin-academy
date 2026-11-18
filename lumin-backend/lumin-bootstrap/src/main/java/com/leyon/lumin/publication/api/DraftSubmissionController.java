package com.leyon.lumin.publication.api;

import com.leyon.lumin.authoring.api.dto.SubmissionReceipt;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.foundation.web.IdempotentExecutor;
import com.leyon.lumin.publication.service.DraftSubmissionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 提交接入层（05 §3.2 submitDraft；URL 沿契约 /api/v1/drafts/{id}/submissions，
 * 编排权归 publication 域——提交创建的是发布流程，02 §3.1 允许边在 publication 侧）。
 * 202 仅表示受理，客户端轮询 GET workflow 读取最终态；幂等回放按存储的 202 响应原样返回。
 */
@RestController
@RequestMapping("/api/v1/drafts/{draftId}/submissions")
public class DraftSubmissionController {

    private final DraftSubmissionService draftSubmissionService;
    private final IdempotentExecutor idempotentExecutor;

    public DraftSubmissionController(DraftSubmissionService draftSubmissionService,
                                     IdempotentExecutor idempotentExecutor) {
        this.draftSubmissionService = draftSubmissionService;
        this.idempotentExecutor = idempotentExecutor;
    }

    @PostMapping
    public ResponseEntity<String> submitDraft(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String draftId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest) {
        return idempotentExecutor.execute(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, ifMatch, Map.of(), () -> {
                    SubmissionReceipt receipt =
                            draftSubmissionService.submit(subject, draftId, ifMatch);
                    return new IdempotentExecutor.ApiResult(202, receipt);
                });
    }
}
