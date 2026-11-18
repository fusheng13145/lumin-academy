package com.leyon.lumin.governance.api;

import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.governance.service.LegalHoldService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 法律保全接入层（docs/04 §5.2 legal_hold；T36：立/释 ADMIN；
 * 保全读取均留痕；USER 保全释放后注销推进由到期扫描续跑）。
 */
@RestController
@RequestMapping("/api/v1/legal-holds")
public class LegalHoldController {

    private final LegalHoldService service;

    public LegalHoldController(LegalHoldService service) {
        this.service = service;
    }

    /** 立保全（ADMIN；subject_type USER/WORK/CASE）。 */
    @PostMapping
    public ApiEnvelope create(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String stubRole,
            @RequestBody CreateHoldRequest request) {
        String id = service.create(subject, stubRole, request.subjectType(),
                request.subjectId(), request.reason(),
                LocalDateTime.now(ZoneOffset.UTC));
        return ApiEnvelope.ok(Map.of("id", id, "state", "ACTIVE"));
    }

    /** 释保全（ADMIN；USER 保全释放后注销推进续跑）。 */
    @PostMapping("/{id}/releases")
    public ApiEnvelope release(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE) String stubRole,
            @PathVariable String id) {
        String state = service.release(subject, stubRole, id,
                LocalDateTime.now(ZoneOffset.UTC));
        return ApiEnvelope.ok(Map.of("id", id, "state", state));
    }

    /** 立保全请求体（basis 引用以 reason 登记并加密留痕）。 */
    public record CreateHoldRequest(String subjectType, String subjectId, String reason) {
    }
}

