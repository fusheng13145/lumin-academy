package com.leyon.lumin.catalog.api;

import com.leyon.lumin.catalog.repository.AssetUploadRow;
import com.leyon.lumin.catalog.service.AssetUploadService;
import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * 素材上传接入层（contracts/p0.openapi asset-uploads 切片；UGC-010 一阶段）：
 * 申请私有单键 PUT → 客户端 PUT 原始字节（签名授权、键隔离）→ 完成校验
 * （大小/摘要/魔数/像素/去 EXIF）→ COMPLETE 签发正式 assetId；
 * 受控内容读取（COVER 限本作品作者；EVIDENCE 限案件作者或有效领取人；
 * 越权与不存在统一 404；nosniff、不重定向源站）。
 */
@RestController
@RequestMapping("/api/v1")
public class AssetUploadController {

    private final AssetUploadService service;

    public AssetUploadController(AssetUploadService service) {
        this.service = service;
    }

    /** 申请私有单键 PUT（签发 300 秒授权；幂等重放不延长有效期）。 */
    @PostMapping("/asset-uploads")
    public ApiEnvelope create(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateAssetUploadRequest request) {
        var grant = service.create(subject, request.purpose(), request.workId(),
                request.caseId(), request.contentType(), request.sizeBytes(),
                request.sha256(), LocalDateTime.now(ZoneOffset.UTC));
        // 授权 cipher 经 base64url 内联于 PUT URL（300 秒、键隔离、AAD 绑定）
        String putUrl = "/internal/asset-objects?grant="
                + java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(grant.grantCipher());
        return ApiEnvelope.ok(Map.of(
                "id", grant.id(),
                "createdBy", grant.createdBy(),
                "createdAt", grant.createdAt(),
                "request", Map.of("purpose", grant.purpose(),
                        "contentType", grant.contentType(),
                        "sizeBytes", grant.sizeBytes(), "sha256", grant.sha256()),
                "state", grant.state(),
                "revision", grant.revision(),
                "method", "PUT",
                "uploadUrl", putUrl,
                "expiresAt", grant.expiresAt(),
                "requiredHeaders", Map.of("Content-Type", "application/octet-stream")));
    }

    /** 上传会话状态（仅上传者本人；只有 COMPLETE 返回非空 assetId）。 */
    @GetMapping("/asset-uploads/{id}")
    public ApiEnvelope status(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String id) {
        AssetUploadRow row = service.getMine(subject, id);
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("id", row.id());
        data.put("createdBy", row.requestedBy());
        data.put("createdAt", row.createdAt().toInstant(ZoneOffset.UTC).toString());
        data.put("request", Map.of("purpose", row.purpose(), "contentType", row.contentType(),
                "sizeBytes", row.sizeBytes(), "sha256", row.sha256Raw()));
        data.put("state", row.state());
        data.put("revision", row.lockVersion());
        data.put("expiresAt", row.expiresAt().toInstant(ZoneOffset.UTC).toString());
        data.put("assetId", row.assetId());
        data.put("failureReason", row.failureReason());
        return ApiEnvelope.ok(data);
    }

    /** 完成校验（上传者本人；CHECKING → COMPLETE/REJECTED）。 */
    @PostMapping("/asset-uploads/{id}/completions")
    public ApiEnvelope complete(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String id,
            @RequestHeader("If-Match") String ifMatch) {
        long expectedLock = com.leyon.lumin.foundation.web.IfMatch.requireValid(ifMatch);
        var result = service.complete(subject, id, LocalDateTime.now(ZoneOffset.UTC));
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("assetId", result.assetId());
        data.put("state", result.state());
        return ApiEnvelope.ok(data);
    }

    /** 隔离对象 PUT（签名授权 300 秒；大小/摘要以实际字节复验 → CHECKING）。 */
    @PutMapping("/internal/asset-objects")
    public ApiEnvelope putObject(
            @RequestParam("grant") String grantCipher,
            @RequestBody byte[] body) throws Exception {
        var envelope = service.verifyGrantSignature(java.util.Base64.getUrlDecoder()
                .decode(grantCipher));
        if (envelope.expiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            // 签名过期不可延长，需新建上传会话（05 contract AssetUploadView）
            throw new com.leyon.lumin.foundation.LuminBusinessException(
                    com.leyon.lumin.foundation.ErrorCodes.ErrorCode.A0001);
        }
        service.putRawObject(envelope.objectKey(), body, LocalDateTime.now(ZoneOffset.UTC));
        return ApiEnvelope.ok(Map.of("state", "CHECKING"));
    }

    /** 受控图片二进制（不使用 JSON 成功信封；nosniff；不重定向源站）。 */
    @GetMapping(value = "/assets/{id}/content", produces = {MediaType.IMAGE_JPEG_VALUE,
            MediaType.IMAGE_PNG_VALUE})
    public org.springframework.http.ResponseEntity<byte[]> content(
            jakarta.servlet.http.HttpServletRequest request,
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String id) throws Exception {
        var asset = service.readContent(subject, id);
        var bytes = service.readFormalBytes(asset);
        return org.springframework.http.ResponseEntity.ok()
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Type", asset.contentType())
                .body(bytes);
    }

    /** 上传申请请求体（purpose/workId/caseId 归属由服务端校验）。 */
    public record CreateAssetUploadRequest(String purpose, String workId, String caseId,
                                           String contentType, Integer sizeBytes,
                                           String sha256) {
    }
}
