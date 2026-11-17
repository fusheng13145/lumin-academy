package com.leyon.lumin.governance.api;

import com.leyon.lumin.foundation.web.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.foundation.web.IdempotentExecutor;
import com.leyon.lumin.governance.service.ReportService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 举报接入层（05 §3.3 createReport；举报人不泄露——服务层统一以替身主体审计）。 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reportService;
    private final IdempotentExecutor idempotentExecutor;

    public ReportController(ReportService reportService,
                            IdempotentExecutor idempotentExecutor) {
        this.reportService = reportService;
        this.idempotentExecutor = idempotentExecutor;
    }

    public record ReportRequest(String workId, String chapterId, String reasonCode,
                                String description) {
    }

    @PostMapping
    public ResponseEntity<String> createReport(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @RequestBody ReportRequest request) {
        return idempotentExecutor.execute(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, null, request,
                () -> new IdempotentExecutor.ApiResult(200, Map.of(
                        "caseId", reportService.createReport(subject, request.workId(),
                                request.chapterId(), request.reasonCode(),
                                request.description()),
                        "state", "ACCEPTED")));
    }
}
