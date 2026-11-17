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
import java.util.List;
import java.time.ZoneOffset;

/** 作品接入层（05 §3.2：listMyWorks / createWork；作品元数据审核走草稿-版本链路）。 */
@RestController
@RequestMapping("/api/v1/works")
public class WorkController {

    private final WorkService workService;
    private final IdempotentExecutor idempotentExecutor;

    public WorkController(WorkService workService, IdempotentExecutor idempotentExecutor) {
        this.workService = workService;
        this.idempotentExecutor = idempotentExecutor;
    }

    /** 私有作品列表（05 §3.2 listMyWorks：仅本人，含 DRAFT 与最新版本状态）。 */
    @org.springframework.web.bind.annotation.GetMapping("/me")
    public org.springframework.http.ResponseEntity<com.leyon.lumin.foundation.web.ApiEnvelope> listMyWorks(
            @org.springframework.web.bind.annotation.RequestAttribute(
                    DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject) {
        var works = workService.listMyWorks(subject).stream()
                .map(w -> new MyWorkView(w.id(), w.businessState(), w.copyrightStatus(),
                        w.draftId(), w.draftState(), w.draftTitle(),
                        w.latestTitle(), w.latestVersionState()))
                .toList();
        return org.springframework.http.ResponseEntity.ok()
                .body(com.leyon.lumin.foundation.web.ApiEnvelope.ok(works));
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

    /** 私有作品列表项（05 §3.2：含可编辑草稿状态与最新版本摘要）。 */
    public record MyWorkView(String id, String businessState, String copyrightStatus,
                             String draftId, String draftState, String draftTitle,
                             String latestTitle, String latestVersionState) {
    }

    /** 作者作品的章节列表（05 §3.2 listMyChapters；仅本人）。 */
    @org.springframework.web.bind.annotation.GetMapping("/{workId}/chapters/me")
    public org.springframework.http.ResponseEntity<com.leyon.lumin.foundation.web.ApiEnvelope> listMyChapters(
            @org.springframework.web.bind.annotation.RequestAttribute(
                    DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @org.springframework.web.bind.annotation.PathVariable String workId) {
        var chapters = workService.listMyChapters(subject, workId).stream()
                .map(c -> new ChapterListItem(c.id(), c.sequenceNo(), c.businessState(),
                        c.draftId(), c.draftState(), c.draftTitle()))
                .toList();
        return org.springframework.http.ResponseEntity.ok()
                .body(com.leyon.lumin.foundation.web.ApiEnvelope.ok(chapters));
    }

    /** 私有章节列表项。 */
    public record ChapterListItem(String id, int sequenceNo, String businessState,
                                  String draftId, String draftState, String draftTitle) {
    }
}
