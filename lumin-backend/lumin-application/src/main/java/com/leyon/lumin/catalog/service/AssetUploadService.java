package com.leyon.lumin.catalog.service;

import com.leyon.lumin.catalog.repository.AssetUploadMapper;
import com.leyon.lumin.catalog.repository.AssetUploadRow;
import com.leyon.lumin.catalog.repository.ContentAssetMapper;
import com.leyon.lumin.catalog.repository.ContentAssetRow;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.storage.ContentObjectStore;
import org.springframework.stereotype.Service;

import java.util.List;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * 素材上传应用服务（docs/13 UGC-010 一阶段；contracts/p0.openapi asset-uploads）：
 * 隔离上传（COVER 绑定本 AUTHOR 作品；EVIDENCE 绑定案件）→ 客户端 PUT 原始字节
 * （对象键隔离、签名 300 秒）→ 完成校验：实际大小/原始 SHA-256/MIME 魔数/
 * 解码像素 ≤2500 万 → ImageIO 重编码去 EXIF（净化字节另计正式 sha256，与
 * 原始摘要分开）→ COMPLETE 签发正式 assetId；格式非法 REJECTED；
 * 存储依赖不可用保持 CHECKING 阻断（不签发 assetId）。
 * COVER 仅本作品可引用；EVIDENCE 绑定上传时案件不得改绑。
 */
@Service
public class AssetUploadService {

    public static final int MAX_SIZE_BYTES = 5242880;
    public static final int MAX_PIXELS = 25_000_000;
    /** 签名 300 秒（05 contract AssetUploadGrant：expiresAt=createdAt+300s）。 */
    public static final int GRANT_TTL_SECONDS = 300;

    private final AssetUploadMapper uploadMapper;
    private final ContentAssetMapper assetMapper;
    private final ContentObjectStore objectStore;
    private final com.leyon.lumin.catalog.service.WorkService workService;
    private final com.leyon.lumin.foundation.crypto.FieldCipher fieldCipher;
    private final com.leyon.lumin.moderation.repository.ReviewCaseMapper reviewCaseMapper;

    public AssetUploadService(AssetUploadMapper uploadMapper, ContentAssetMapper assetMapper,
                              ContentObjectStore objectStore,
                              com.leyon.lumin.catalog.service.WorkService workService,
                              com.leyon.lumin.foundation.crypto.FieldCipher fieldCipher,
                              com.leyon.lumin.moderation.repository.ReviewCaseMapper reviewCaseMapper) {
        this.uploadMapper = uploadMapper;
        this.assetMapper = assetMapper;
        this.objectStore = objectStore;
        this.workService = workService;
        this.fieldCipher = fieldCipher;
        this.reviewCaseMapper = reviewCaseMapper;
    }

    /** 申请私有单键 PUT（COVER 绑定本 AUTHOR 作品；EVIDENCE 绑定案件）。 */
    @Transactional
    public Grant create(String subject, String purpose, String workId, String caseId,
                        String contentType, int sizeBytes, String sha256, LocalDateTime now) {
        if (!List.of("COVER", "EVIDENCE").contains(purpose)
                || !List.of("image/jpeg", "image/png").contains(contentType)
                || sizeBytes < 1 || sizeBytes > MAX_SIZE_BYTES
                || sha256 == null || !sha256.matches("[a-f0-9]{64}")) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        if ("COVER".equals(purpose)) {
            if (workId == null || caseId != null) {
                throw new LuminBusinessException(ErrorCode.P1001);
            }
            // 归属校验：COVER 仅本 AUTHOR 的作品（04 §2；归属从权威记录读取）。
            // 2026-11-20 安全审查修复：原实现用无归属语义的 getWorkSummary，只判作品存在，
            // 作者 A 可把封面绑定到作者 B 的作品；改为 requireOwnedWork（不存在/越权统一 404）。
            workService.requireOwnedWork(workId, subject);
        } else if (caseId == null || workId != null) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        String id = UUID.randomUUID().toString();
        String objectKey = "asset-upload/" + id + "/" + sha256 + ".bin";
        String state = "AWAITING_UPLOAD";
        LocalDateTime expiresAt = now.plusSeconds(GRANT_TTL_SECONDS);
        uploadMapper.insert(new AssetUploadRow(id, purpose, workId, caseId, contentType,
                sizeBytes, sha256, objectKey, state, null, null, subject, expiresAt,
                now, now, subject, subject, 1L));
        byte[] signature = fieldCipher.encrypt(objectKey + "|" + sha256 + "|"
                + expiresAt.toInstant(ZoneOffset.UTC), "asset_upload.put_grant");
                // 客户端 PUT 地址（bootstrap 控制器 /api/v1/internal/asset-objects 前缀
        //  由消费方拼装——授权 cipher 自包含，本地/对象存储适配等价） */
        return new Grant(id, subject, now.toInstant(ZoneOffset.UTC).toString(), purpose,
                workId, caseId, contentType, sizeBytes, sha256, state, 1L, expiresAt
                .toInstant(ZoneOffset.UTC).toString(), signature);
    }

    /** 授权解包：签名校验（AAD 绑定用途）失败/过期 → 401/A0001。 */
    public GrantEnvelope verifyGrantSignature(byte[] grantCipher) {
        try {
            String packed = fieldCipher.decrypt(grantCipher, "asset_upload.put_grant");
            String[] parts = packed.split("\\|", -1);
            if (parts.length != 3) {
                throw new IllegalStateException("签名载荷非法");
            }
            return new GrantEnvelope(parts[0], parts[1],
                    LocalDateTime.parse(parts[2].replace("Z", "")));
        } catch (IllegalStateException | java.time.format.DateTimeParseException e) {
            // 载荷非法 / 到期时间戳不可解析 → 一律 401（原实现的 catch(LuminBusinessException)
            // 分支不可能触发，而 DateTimeParseException 会漏成 500——2026-11-20 修复）
            throw new LuminBusinessException(ErrorCode.A0001);
        }
    }

    /** PUT 原始字节：键隔离复验 + 大小/摘要核验 → CHECKING（净化校验随完成触发）。 */
    @Transactional
    public void putRawObject(String objectKey, byte[] body, LocalDateTime now) {
        AssetUploadRow row = uploadMapper.findByObjectKeyForUpdate(objectKey);
        if (row == null || !"AWAITING_UPLOAD".equals(row.state())) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        if (body == null || body.length != row.sizeBytes()) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        String actualSha = sha256Hex(body);
        if (!actualSha.equals(row.sha256Raw())) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        objectStore.putImmutable(objectKey, body);
        if (uploadMapper.transition(row.id(), "CHECKING", null, null, row.state(),
                row.lockVersion(), row.requestedBy(), now) == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
    }

    /** 完成校验（上传者本人）：魔数/解码像素 ≤2500 万/重编码去 EXIF → COMPLETE。 */
    @Transactional
    public CompleteResult complete(String subject, String uploadId, LocalDateTime now) {
        AssetUploadRow row = uploadMapper.findById(uploadId);
        if (row == null || !row.requestedBy().equals(subject)) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        if (!"CHECKING".equals(row.state())) {
            // AWAITING_UPLOAD → 先收字节再完成；COMPLETE 幂等回原结果
            if ("COMPLETE".equals(row.state())) {
                return new CompleteResult(row.assetId(), row.state());
            }
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        byte[] raw;
        try {
            raw = objectStore.read(row.objectKey());
        } catch (Exception storageFailure) {
            // 依赖不可用：保持 CHECKING 阻断，不签发 assetId（05 contract）
            throw new LuminBusinessException(ErrorCode.D2001);
        }
        // MIME 魔数
        if (!hasValidMagic(raw, row.contentType())) {
            reject(row, "INVALID_MAGIC", now);
            return new CompleteResult(null, "REJECTED");
        }
        BufferedImage image;
        try {
            String format = row.contentType().endsWith("png") ? "png" : "jpeg";
            image = ImageIO.read(new ByteArrayInputStream(raw));
            if (image == null) {
                reject(row, "NOT_DECODABLE", now);
                return new CompleteResult(null, "REJECTED");
            }
            if ((long) image.getWidth() * image.getHeight() > MAX_PIXELS) {
                reject(row, "PIXEL_LIMIT", now);
                return new CompleteResult(null, "REJECTED");
            }
            // 重编码去 EXIF（净化）：净化字节写入正式对象，另计正式 sha256
            ByteArrayOutputStream cleaned = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, cleaned)) {
                reject(row, "RE_ENCODE_FAILED", now);
                return new CompleteResult(null, "REJECTED");
            }
            byte[] cleanBytes = cleaned.toByteArray();
            String formalSha = sha256Hex(cleanBytes);
            String formalKey = "asset/" + row.purpose().toLowerCase() + "/"
                    + row.id() + "/" + formalSha + "." + format;
            objectStore.putImmutable(formalKey, cleanBytes);
            String assetId = UUID.randomUUID().toString();
            assetMapper.insert(new ContentAssetRow(assetId, row.purpose(), row.workId(),
                    row.caseId(), row.contentType(), cleanBytes.length, formalSha,
                    formalKey, now, now, row.requestedBy(), row.requestedBy(), 1L));
            if (uploadMapper.transition(row.id(), "COMPLETE", assetId, null,
                    "CHECKING", row.lockVersion(), subject, now) == 0) {
                throw new LuminBusinessException(ErrorCode.B2002);
            }
            return new CompleteResult(assetId, "COMPLETE");
        } catch (LuminBusinessException e) {
            throw e;
        } catch (java.io.IOException e) {
            // 解码 I/O 失败视为格式非法（REJECTED 留痕；I/O 异常不回滚 REJECTED）
            reject(row, "NOT_DECODABLE", now);
            return new CompleteResult(null, "REJECTED");
        }
    }

    /** 状态视图（仅上传者本人）。 */
    @Transactional(readOnly = true)
    public AssetUploadRow getMine(String subject, String uploadId) {
        AssetUploadRow row = uploadMapper.findById(uploadId);
        if (row == null || !row.requestedBy().equals(subject)) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        return row;
    }

    /** 正式资源内容（COVER 限本作品作者；EVIDENCE 限案件作者或当前领取人）。 */
    @Transactional(readOnly = true)
    public ContentAssetRow readContent(String subject, String assetId) {
        ContentAssetRow asset = assetMapper.findById(assetId);
        if (asset == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        boolean allowed = false;
        if ("COVER".equals(asset.purpose()) && asset.workId() != null) {
            var work = workService.getWorkSummary(asset.workId());
            allowed = work != null && work.authorId() != null
                    && work.authorId().equals(subject);
        } else if ("EVIDENCE".equals(asset.purpose()) && asset.caseId() != null) {
            // EVIDENCE：案件作者（工作作者）或当前有效领取人（05 contract 读取口径）
            var caseRow = reviewCaseMapper.findById(asset.caseId());
            String assignee = caseRow == null ? null : caseRow.assigneeId();
            String workAuthor = null;
            if (caseRow != null && caseRow.workId() != null) {
                var work = workService.getWorkSummary(caseRow.workId());
                workAuthor = work == null ? null : work.authorId();
            }
            allowed = (assignee != null && assignee.equals(subject))
                    || (workAuthor != null && workAuthor.equals(subject));
        }
        if (!allowed) {
            // 越权与不存在统一 404（05 contract：不返回删减后冒充完整证据）
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        return asset;
    }

    private void reject(AssetUploadRow row, String reasonCode, LocalDateTime now) {
        uploadMapper.transition(row.id(), "REJECTED", null, reasonCode, row.state(),
                row.lockVersion(), "system", now);
    }

    /** 读取净化后正式字节（受控；调用方完成授权校验）。 */
    @Transactional(readOnly = true)
    public byte[] readFormalBytes(ContentAssetRow asset) {
        return objectStore.read(asset.objectKey());
    }

    private static boolean hasValidMagic(byte[] raw, String contentType) {
        if (raw.length < 4) {
            return false;
        }
        if (contentType.endsWith("jpeg")) {
            return (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xD8 && (raw[2] & 0xFF) == 0xFF;
        }
        return (raw[0] & 0xFF) == 0x89 && (raw[1] & 0xFF) == 0x50
                && (raw[2] & 0xFF) == 0x4E && (raw[3] & 0xFF) == 0x47;
    }

    private static String sha256Hex(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256 实现", e);
        }
    }

    /** 签发回执（uploadUrl 仅含签名 cipher 引用——客户端经 PUT 端点提交）。 */
    public record Grant(String id, String createdBy, String createdAt, String purpose,
                        String workId, String caseId, String contentType, Integer sizeBytes,
                        String sha256, String state, Long revision, String expiresAt,
                        byte[] grantCipher) {
    }

    /** 授权解包（对象键/摘要/到期）。 */
    public record GrantEnvelope(String objectKey, String sha256, LocalDateTime expiresAt) {
    }

    /** 完成结果（assetId 仅 COMPLETE 非空）。 */
    public record CompleteResult(String assetId, String state) {
    }
}
