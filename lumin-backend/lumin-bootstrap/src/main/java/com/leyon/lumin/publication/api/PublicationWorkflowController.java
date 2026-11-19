package com.leyon.lumin.publication.api;

import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.foundation.web.IdempotentExecutor;
import com.leyon.lumin.publication.service.PublicationWorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 发布流程详情与作者撤回（05 §3.2 getWorkflow / cancelWorkflow）。 */
@RestController
@RequestMapping("/api/v1/publication-workflows")
public class PublicationWorkflowController {

    private final PublicationWorkflowService workflowService;
    private final IdempotentExecutor idempotentExecutor;

    public PublicationWorkflowController(PublicationWorkflowService workflowService,
                                          IdempotentExecutor idempotentExecutor) {
        this.workflowService = workflowService;
        this.idempotentExecutor = idempotentExecutor;
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiEnvelope> getWorkflow(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String id) {
        PublicationWorkflowService.WorkflowView view = workflowService.getVisible(subject, id);
        return ResponseEntity.ok()
                .eTag("\"" + view.revision() + "\"")
                .body(ApiEnvelope.ok(view));
    }

    @PostMapping("/{id}/cancellations")
    public ResponseEntity<String> cancelWorkflow(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @RequestBody ReasonRequest request) {
        return idempotentExecutor.execute(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, ifMatch, request,
                () -> new IdempotentExecutor.ApiResult(200,
                        workflowService.cancel(subject, id, ifMatch, request.reason(),
                                LocalDateTime.now(ZoneOffset.UTC))));
    }

    /** 05 §3.2 ReasonRequest。 */
    public record ReasonRequest(String reason) {
    }
}
