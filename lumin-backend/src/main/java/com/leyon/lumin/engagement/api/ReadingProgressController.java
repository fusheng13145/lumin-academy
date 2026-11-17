package com.leyon.lumin.engagement.api;

import com.leyon.lumin.engagement.repository.ReadingProgressRow;
import com.leyon.lumin.engagement.service.ReadingProgressService;
import com.leyon.lumin.foundation.web.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 阅读进度接入层（05 §3.4：PUT/GET /me/reading-progress/{id}，id=章节 ID）。
 * 本人且可读该章节才可写（T29）；乐观锁 If-Match，首次写 "0"；
 * 重复保存返回新 ETag 与 versionChanged 标记（UI 版本变更提示依据）。
 */
@RestController
@RequestMapping("/api/v1/me/reading-progress")
public class ReadingProgressController {

    private final ReadingProgressService progressService;

    public ReadingProgressController(ReadingProgressService progressService) {
        this.progressService = progressService;
    }

    /** 保存进度（边界合法 0–10000 bp / read_seconds ≥ 0 / 客户端时钟超前 ≤5 分钟）。 */
    @PutMapping("/{id}")
    public ApiEnvelope save(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String id,
            @RequestHeader("If-Match") String ifMatch,
            @RequestBody SaveProgressRequest request) {
        var result = progressService.save(subject, id, ifMatch,
                request.positionBasisPoints(), request.readSeconds(),
                request.clientUpdatedAt() == null ? null
                        : java.time.Instant.parse(request.clientUpdatedAt()));
        return ApiEnvelope.ok(new SaveProgressView(String.valueOf(result.etag()),
                result.versionChanged()));
    }

    /** 本人进度（位置、版本与 ETag；不存在 404；越权由按主体查询隔离）。 */
    @GetMapping("/{id}")
    public ApiEnvelope get(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String id) {
        ReadingProgressRow row = progressService.get(subject, id);
        return ApiEnvelope.ok(new ProgressView(row.chapterId(), row.versionId(),
                row.positionBasisPoints(), row.readSeconds(),
                row.clientUpdatedAt().toInstant(java.time.ZoneOffset.UTC).toString(),
                String.valueOf(row.lockVersion())));
    }

    /** 保存请求体（05 §3.4 saveProgress；clientUpdatedAt ISO-8601）。 */
    public record SaveProgressRequest(Integer positionBasisPoints, Integer readSeconds,
                                      String clientUpdatedAt) {
    }

    /** 保存响应（新 ETag + 版本重绑标记）。 */
    public record SaveProgressView(String etag, boolean versionChanged) {
    }

    /** 进度视图（契约：位置、版本与 ETag）。 */
    public record ProgressView(String chapterId, String versionId, Integer positionBasisPoints,
                               Integer readSeconds, String clientUpdatedAt, String etag) {
    }
}
