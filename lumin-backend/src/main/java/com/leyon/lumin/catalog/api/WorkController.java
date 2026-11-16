package com.leyon.lumin.catalog.api;


import tools.jackson.databind.ObjectMapper;
import com.leyon.lumin.catalog.service.WorkService;
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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.function.Supplier;

/** 作品接入层（05 §3.2 createWork；作品元数据审核走草稿-版本链路，无公开 PATCH 旁路）。 */
@RestController
@RequestMapping("/api/v1/works")
public class WorkController {

    private final WorkService workService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public WorkController(WorkService workService, IdempotencyService idempotencyService,
                          ObjectMapper objectMapper) {
        this.workService = workService;
        this.idempotencyService = idempotencyService;
        this.objectMapper = objectMapper;
    }

    @PostMapping
    public ResponseEntity<String> createWork(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            jakarta.servlet.http.HttpServletRequest httpRequest,
            @RequestBody CreateWorkRequest request) {
        if (IdempotencyRules.validateKey(idempotencyKey) != ErrorCode.OK) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        String digest = IdempotencyRules.requestDigest(
                objectMapper.writeValueAsString(request));
        IdempotencyService.Scope scope = new IdempotencyService.Scope(subject, "POST",
                IdempotencyRules.normalizePath(httpRequest.getRequestURI()), idempotencyKey,
                digest, LocalDateTime.now(ZoneOffset.UTC));
        var replay = idempotencyService.findReplay(scope);
        if (replay.isPresent()) {
            IdempotencyRow stored = replay.get();
            return ResponseEntity.status(stored.responseCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(stored.responseBody());
        }
        Supplier<ReceiptView> action = () -> new ReceiptView(
                workService.createWork(subject, LocalDateTime.now(ZoneOffset.UTC)),
                "work", "DRAFT", 1L);
        ReceiptView receipt = action.get();
        String body = objectMapper.writeValueAsString(ApiEnvelope.ok(receipt));
        idempotencyService.record(scope, 200, body);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
