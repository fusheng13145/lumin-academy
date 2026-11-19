package com.leyon.lumin.identity.api;

import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.foundation.web.IdempotentExecutor;
import com.leyon.lumin.identity.service.AgeVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 年龄核验接入层（05 §3.1 verifyAge；docs/13 UGC-003）：只接收 proofRef，
 * 核验结论由服务端受信适配器回查；写操作要求 Idempotency-Key。
 */
@RestController
public class AgeVerificationController {

    private final AgeVerificationService service;
    private final IdempotentExecutor idempotentExecutor;

    public AgeVerificationController(AgeVerificationService service,
                                     IdempotentExecutor idempotentExecutor) {
        this.service = service;
        this.idempotentExecutor = idempotentExecutor;
    }

    @PostMapping("/api/v1/me/age-checks")
    public ResponseEntity<String> verify(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @RequestBody ProofRequest request) {
        return idempotentExecutor.execute(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, null, request,
                () -> new IdempotentExecutor.ApiResult(200,
                        service.verify(subject, request.proofRef(),
                                LocalDateTime.now(ZoneOffset.UTC))));
    }

    /** 契约 ProofRequest：客户端只提交核验凭据引用，不提交年龄结论。 */
    public record ProofRequest(String proofRef) {
    }
}
