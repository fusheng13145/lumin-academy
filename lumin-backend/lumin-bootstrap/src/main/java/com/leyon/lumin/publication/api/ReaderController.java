package com.leyon.lumin.publication.api;

import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.publication.service.ReaderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 读者接入层（05 §3.4：listWorks / getWork / readChapter）。
 * 每次请求经三态裁决（VisibilityPolicy）+ 下架闸门；未授权统一 404；
 * 正文不经公开对象地址，由可见性闸门返回（05 §5.1）。
 */
@RestController
@RequestMapping("/api/v1")
public class ReaderController {

    private final ReaderService readerService;

    public ReaderController(ReaderService readerService) {
        this.readerService = readerService;
    }

    /** 作品列表（UGC-009：签名字游标分页——data 契约为数组，nextCursor 经响应头传递；
     *  limit 默认 20 上限 100，失效/过期/篡改/跨主体游标 400）。 */
    @GetMapping("/works")
    public ApiEnvelope listWorks(
            @org.springframework.web.bind.annotation.RequestAttribute(
                    com.leyon.lumin.foundation.web.DemoAuthenticationFilter.ATTRIBUTE_SUBJECT)
            java.lang.String subject,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            java.lang.String cursor,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            java.lang.Integer limit,
            jakarta.servlet.http.HttpServletResponse response) {
        var page = readerService.listWorks(subject, cursor, limit);
        if (page.nextCursor() != null) {
            response.setHeader("X-Lumin-Next-Cursor", page.nextCursor());
        }
        return ApiEnvelope.ok(page.items());
    }

    /** 元数据搜索（UGC-038 一阶段：标题/作者、分类、标签至少一个条件；
     *  可见性与 /works 同源；签名字游标同口径；空结果 200 空数组与不可用分开）。 */
    @GetMapping("/works/search")
    public ApiEnvelope searchWorks(
            @org.springframework.web.bind.annotation.RequestAttribute(
                    com.leyon.lumin.foundation.web.DemoAuthenticationFilter.ATTRIBUTE_SUBJECT)
            java.lang.String subject,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            java.lang.String q,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            java.lang.String categoryId,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            java.lang.String tag,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            java.lang.String cursor,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            java.lang.Integer limit,
            jakarta.servlet.http.HttpServletResponse response) {
        var page = readerService.searchWorks(subject, q, categoryId, tag, cursor, limit);
        if (page.nextCursor() != null) {
            response.setHeader("X-Lumin-Next-Cursor", page.nextCursor());
        }
        return ApiEnvelope.ok(page.items());
    }

    /** 作品详情：三态裁决通过返回最新公开元数据，未授权统一 404。 */
    @GetMapping("/works/{id}")
    public ApiEnvelope getWork(
            @RequestAttribute(com.leyon.lumin.foundation.web.DemoAuthenticationFilter.ATTRIBUTE_SUBJECT)
            String subject,
            @PathVariable String id) {
        return readerService.getWork(subject, id)
                .map(ApiEnvelope::ok)
                .orElseThrow(() -> new com.leyon.lumin.foundation.LuminBusinessException(
                        com.leyon.lumin.foundation.ErrorCodes.ErrorCode.B2004));
    }

    /** 章节目录（05 §3.4 listChapters：仅可见章节，sequenceNo 稳定排序）。 */
    @GetMapping("/works/{id}/chapters")
    public ApiEnvelope listChapters(
            @RequestAttribute(com.leyon.lumin.foundation.web.DemoAuthenticationFilter.ATTRIBUTE_SUBJECT)
            String subject,
            @PathVariable String id) {
        return ApiEnvelope.ok(readerService.listChapters(subject, id));
    }

    /** 章节正文：三态裁决通过返回最新通过版本纯文本（不接受 versionId 指定历史）。 */
    @GetMapping("/chapters/{id}/content")
    public ApiEnvelope readChapter(
            @RequestAttribute(com.leyon.lumin.foundation.web.DemoAuthenticationFilter.ATTRIBUTE_SUBJECT)
            String subject,
            @PathVariable String id) {
        return readerService.readChapter(subject, id)
                .map(ApiEnvelope::ok)
                .orElseThrow(() -> new com.leyon.lumin.foundation.LuminBusinessException(
                        com.leyon.lumin.foundation.ErrorCodes.ErrorCode.B2004));
    }
}

