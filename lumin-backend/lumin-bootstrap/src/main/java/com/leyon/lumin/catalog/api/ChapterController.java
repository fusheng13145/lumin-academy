package com.leyon.lumin.catalog.api;

import com.leyon.lumin.catalog.service.WorkService;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.foundation.web.IdempotentExecutor;
import com.leyon.lumin.foundation.web.ReceiptView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 章节接入层（05 §3.2 createChapter）：If-Match 为作品 ETag（新增章节改写
 * next_chapter_no，与完结操作互斥取同一作品行锁，03 §4.2）；完结作品禁止新章。
 */
@RestController
@RequestMapping("/api/v1/works/{workId}/chapters")
public class ChapterController {

    private final WorkService workService;
    private final IdempotentExecutor idempotentExecutor;

    public ChapterController(WorkService workService, IdempotentExecutor idempotentExecutor) {
        this.workService = workService;
        this.idempotentExecutor = idempotentExecutor;
    }

    @PostMapping
    public ResponseEntity<String> createChapter(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String workId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest httpRequest,
            @RequestBody Map<String, Object> request) {
        return idempotentExecutor.execute(subject, "POST", httpRequest.getRequestURI(),
                idempotencyKey, ifMatch, request,
                () -> new IdempotentExecutor.ApiResult(200, new ReceiptView(
                        workService.createChapter(subject, workId, ifMatch,
                                LocalDateTime.now(ZoneOffset.UTC)),
                        "chapter", "DRAFT", 1L)));
    }
}
