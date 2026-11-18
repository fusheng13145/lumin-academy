package com.leyon.lumin.identity.api;

import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.identity.repository.AccountClosureRow;
import com.leyon.lumin.identity.service.AccountClosureService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneOffset;

/**
 * 注销接入层（03 §8 / T36：POST /me/closure 注销申请——公开关联立即断开；
 * 内部关联匿名化由 SYSTEM 按到期推进；GET 查询本人推进状态）。
 */
@RestController
@RequestMapping("/api/v1/me")
public class ClosureController {

    private final AccountClosureService service;
    private final com.leyon.lumin.foundation.port.LegalHoldPort legalHoldPort;

    public ClosureController(AccountClosureService service,
                             com.leyon.lumin.foundation.port.LegalHoldPort legalHoldPort) {
        this.service = service;
        this.legalHoldPort = legalHoldPort;
    }

    /** 注销申请（本人；有 ACTIVE 法律保全时内部匿名化暂缓——推进扫描跳过）。 */
    @PostMapping("/closure")
    public ApiEnvelope close(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject) {
        AccountClosureRow row = service.requestClosure(subject,
                java.time.LocalDateTime.now(ZoneOffset.UTC));
        return ApiEnvelope.ok(toView(row, legalHoldPort.hasActive("USER", subject)));
    }

    /** 本人注销推进状态（未申请 404；holdActive=保全暂缓中，03 §8 可见）。 */
    @GetMapping("/closure")
    public ApiEnvelope status(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject) {
        return ApiEnvelope.ok(toView(service.get(subject),
                legalHoldPort.hasActive("USER", subject)));
    }

    /** 注销推进视图（state PENDING/ANONYMIZED + 匿名化到期时间 + 保全暂缓标记）。 */
    public record ClosureView(String userId, String state, String requestedAt,
                              String anonymizeDueAt, String holdId, boolean holdActive) {
    }

    private static ClosureView toView(AccountClosureRow row, boolean holdActive) {
        return new ClosureView(row.userId(), row.state(),
                row.requestedAt().toInstant(ZoneOffset.UTC).toString(),
                row.anonymizeDueAt().toInstant(ZoneOffset.UTC).toString(), row.holdId(),
                holdActive);
    }
}

