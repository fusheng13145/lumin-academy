package com.leyon.lumin.catalog.api;

import com.leyon.lumin.catalog.service.WorkService;
import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 作者私有工作区接入层（05 §3.2 契约路径：GET /me/works、GET /me/works/{id}/chapters；
 * 仅本人）。增量十六前曾以 /works/me、/works/{id}/chapters/me 骨架路径运行，
 * 前端按契约路径调用而 404——本控制器对齐契约并移除旧路径（登记于增量十七）。
 */
@RestController
@RequestMapping("/api/v1/me/works")
public class MeWorkspaceController {

    private final WorkService workService;

    public MeWorkspaceController(WorkService workService) {
        this.workService = workService;
    }

    /** 私有作品列表（05 §3.2 listMyWorks：仅本人，含 DRAFT 与治理态，签名游标分页）。 */
    @GetMapping
    public ApiEnvelope listMyWorks(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return ApiEnvelope.ok(workService.listMyWorks(subject, cursor, limit));
    }

    /** 作者作品的章节列表（05 §3.2 listMyChapters；仅本人，签名游标分页）。 */
    @GetMapping("/{workId}/chapters")
    public ApiEnvelope listMyChapters(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String workId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        return ApiEnvelope.ok(workService.listMyChapters(subject, workId, cursor, limit));
    }

    /** 私有作品详情（05 §3.2 getMyWork；不存在/越权统一 404）。 */
    @GetMapping("/{workId}")
    public ResponseEntity<ApiEnvelope> getMyWork(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String workId) {
        WorkService.MyWorkDetail detail = workService.getMyWork(subject, workId);
        AuthorWorkView view = new AuthorWorkView(detail.id(), detail.state(), detail.title(),
                detail.draftId(), detail.draftState(), detail.publishedVersionId(),
                detail.revision());
        return ResponseEntity.ok()
                .eTag("\"" + view.revision() + "\"")
                .body(ApiEnvelope.ok(view));
    }

    /** 契约 AuthorWorkView（05 §3.2）。 */
    public record AuthorWorkView(String id, String state, String title, String draftId,
                                 String draftState, String publishedVersionId, long revision) {
    }
}

