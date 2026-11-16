package com.leyon.lumin.catalog.api;

import com.leyon.lumin.catalog.service.WorkService;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.foundation.web.IdempotentExecutor;
import com.leyon.lumin.foundation.web.ReceiptView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 作品接入层（05 §3.2 createWork；作品元数据审核走草稿-版本链路，无公开 PATCH 旁路）。 */
@RestController
@RequestMapping("/api/v1/works")
public class WorkController {

    private final WorkService workService;
    private final IdempotentExecutor idempotentExecutor;

    public WorkController(WorkService workService, IdempotentExecutor idempotentExecutor) {
        this.workService = workService;
        this.idempotentExecutor = idempotentExecutor;
    }

    @PostMapping
    public ResponseEntity<String> createWork(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @RequestBody CreateWorkRequest request) {
        return idempotentExecutor.execute(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, null, request,
                () -> new IdempotentExecutor.ApiResult(200, new ReceiptView(
                        workService.createWork(subject, LocalDateTime.now(ZoneOffset.UTC)),
                        "work", "DRAFT", 1L)));
    }
}
