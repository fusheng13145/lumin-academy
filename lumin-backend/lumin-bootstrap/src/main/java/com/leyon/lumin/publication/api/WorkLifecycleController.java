package com.leyon.lumin.publication.api;

import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.foundation.web.IdempotentExecutor;
import com.leyon.lumin.publication.service.WorkLifecycleService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 作品生命周期接入层（05 §3.2 changeWorkLifecycle；URL 归作品，编排归 publication）。 */
@RestController
@RequestMapping("/api/v1/works")
public class WorkLifecycleController {

    private final WorkLifecycleService workLifecycleService;
    private final IdempotentExecutor idempotentExecutor;

    public WorkLifecycleController(WorkLifecycleService workLifecycleService,
                                   IdempotentExecutor idempotentExecutor) {
        this.workLifecycleService = workLifecycleService;
        this.idempotentExecutor = idempotentExecutor;
    }

    @PostMapping("/{id}/lifecycle-actions")
    public ResponseEntity<String> changeWorkLifecycle(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String id,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @RequestBody LifecycleRequest request) {
        return idempotentExecutor.execute(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, ifMatch, request,
                () -> new IdempotentExecutor.ApiResult(200,
                        workLifecycleService.change(subject, id, request.action(), ifMatch,
                                request.reason(), LocalDateTime.now(ZoneOffset.UTC))));
    }

    /** 05 §3.2 LifecycleRequest。 */
    public record LifecycleRequest(String action, String reason) {
    }
}
