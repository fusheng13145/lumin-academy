package com.leyon.lumin.authoring.service;


import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.leyon.lumin.authoring.api.dto.DraftContractView;
import com.leyon.lumin.authoring.api.dto.DraftCreateRequest;
import com.leyon.lumin.authoring.api.dto.DraftSaveRequest;
import com.leyon.lumin.authoring.api.dto.DraftView;
import com.leyon.lumin.authoring.domain.DraftContentValidator;
import com.leyon.lumin.authoring.repository.DraftMapper;
import com.leyon.lumin.authoring.repository.DraftRow;
import com.leyon.lumin.catalog.domain.TargetRef;

import com.leyon.lumin.catalog.service.WorkService;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.cursor.CursorCodec;
import com.leyon.lumin.foundation.values.TargetType;
import com.leyon.lumin.foundation.web.IfMatch;
import com.leyon.lumin.foundation.web.ReceiptView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 草稿应用服务（03 §4.1：归属校验、载荷校验、幂等之外的编排；鉴权主体由
 * 接入层从认证上下文注入，INV-07）。年龄/监护/配额校验随提交端点接入
 * SubmissionGate（骨架登记：创建/保存暂不调用）。
 */
@Service
public class DraftCommandService {

    private final DraftMapper draftMapper;
    private final WorkService workService;
    private final ObjectMapper objectMapper;
    private final AuditRecorder auditRecorder;
    private final String cursorKey;

    public DraftCommandService(DraftMapper draftMapper, WorkService workService,
                               ObjectMapper objectMapper, AuditRecorder auditRecorder,
                               @Value("${lumin.cursor.key}") String cursorKey) {
        this.draftMapper = draftMapper;
        this.workService = workService;
        this.objectMapper = objectMapper;
        this.auditRecorder = auditRecorder;
        this.cursorKey = cursorKey;
    }

    /** 契约 DraftPage（05 §3.2 listMyDrafts）。 */
    public record Page(List<DraftContractView> items, String nextCursor) {
    }

    /** 创建 WORK / CHAPTER 草稿（05 §3.2 createDraft；baseVersionId 随修订流落地）。 */
    @Transactional
    public ReceiptView createDraft(String subject, DraftCreateRequest request) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        TargetRef target = targetOf(request);
        List<String> tags = request.tags() == null ? List.of() : request.tags();
        if (DraftContentValidator.validate(target, request.title(), request.synopsis(),
                parseUuid(request.categoryId(), "categoryId"), tags, request.body()) != ErrorCode.OK) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        WorkService.WorkSummary work = workService.requireOwnedWork(request.workId(), subject);
        if (!"VALID".equals(work.copyrightStatus())) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        if (request.chapterId() != null) {
            workService.requireChapterInWork(request.chapterId(), request.workId());
        }
        DraftRow row = new DraftRow(UUID.randomUUID().toString(), request.workId(),
                request.chapterId(), target.targetType().name(), null, request.title(),
                request.synopsis(), request.categoryId(), toJson(tags), null,
                request.body(), "EDITABLE", now, now, subject, subject, 1L, null);
        draftMapper.insert(row);
        return new ReceiptView(row.id(), "draft", "EDITABLE", 1L);
    }

    /** 草稿详情：越权与不存在统一 404 B2004（05 §2）。 */
    @Transactional(readOnly = true)
    public DraftView getDraft(String subject, String id) {
        return toView(requireOwnedDraft(subject, id));
    }

    /** 作者草稿列表（05 §3.2 listMyDrafts：归属过滤后签名游标分页）。 */
    @Transactional(readOnly = true)
    public Page listMyDrafts(String subject, String cursorToken, Integer limitParam,
                             LocalDateTime now) {
        ErrorCode limitCheck = CursorCodec.validateLimit(limitParam);
        if (limitCheck != ErrorCode.OK) {
            throw new LuminBusinessException(limitCheck);
        }
        int limit = CursorCodec.resolveLimit(limitParam);
        String filterDigest = CursorCodec.filterDigest("list=my-drafts;viewer=" + subject);
        LocalDateTime cursorAt = null;
        String cursorId = null;
        if (cursorToken != null && !cursorToken.isBlank()) {
            CursorCodec.DecodedCursor decoded = CursorCodec.decode(cursorToken,
                    cursorKey.getBytes(StandardCharsets.UTF_8),
                    now.toInstant(ZoneOffset.UTC), filterDigest);
            if (decoded.code() != ErrorCode.OK) {
                throw new LuminBusinessException(decoded.code());
            }
            if (!subject.equals(decoded.payload().subjectId().toString())) {
                throw new LuminBusinessException(ErrorCode.P1001);
            }
            try {
                cursorAt = LocalDateTime.parse(decoded.payload().sortKeyValue());
                cursorId = decoded.payload().lastId().toString();
            } catch (RuntimeException e) {
                throw new LuminBusinessException(ErrorCode.P1001);
            }
        }
        List<DraftRow> rows = draftMapper.listByAuthor(subject, cursorAt, cursorId, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<DraftRow> page = rows.subList(0, Math.min(rows.size(), limit));
        List<DraftContractView> items = new ArrayList<>(page.size());
        for (DraftRow row : page) {
            items.add(toContractView(row));
        }
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            DraftRow last = page.get(page.size() - 1);
            nextCursor = CursorCodec.encode(new CursorCodec.Payload(
                    last.updatedAt().toString(), UUID.fromString(last.id()), filterDigest,
                    UUID.fromString(subject), now.toInstant(ZoneOffset.UTC)),
                    cursorKey.getBytes(StandardCharsets.UTF_8));
        }
        return new Page(items, nextCursor);
    }

    /** 丢弃草稿（03 §2.2 DISCARD；仅 EDITABLE，If-Match 保护）。 */
    @Transactional
    public ReceiptView discardDraft(String subject, String id, String ifMatch, String reason) {
        DraftRow row = requireOwnedDraft(subject, id);
        if (!"EDITABLE".equals(row.state())) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        String normalizedReason = normalizeReason(reason);
        long expected = IfMatch.requireValid(ifMatch);
        if (row.lockVersion() != expected) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int rows = draftMapper.discard(id, expected, subject, now);
        if (rows == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, "AUTHOR",
                "draft.discarded", "draft", id, normalizedReason,
                com.leyon.lumin.foundation.contract.ApiEnvelope.currentTraceId(),
                now.toInstant(ZoneOffset.UTC)));
        return new ReceiptView(id, "DRAFT", "DISCARDED", expected + 1);
    }

    /** 保存（03 §2.2：If-Match 匹配 lockVersion 才允许，递增版本；SUBMITTED 冻结）。 */
    @Transactional
    public ReceiptView saveDraft(String subject, String id, String ifMatch,
                                 DraftSaveRequest request) {
        DraftRow row = requireOwnedDraft(subject, id);
        if (!"EDITABLE".equals(row.state())) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        long expected = parseIfMatch(ifMatch);
        if (expected != row.lockVersion()) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        TargetRef target = new TargetRef(TargetType.valueOf(row.targetType()),
                UUID.fromString(row.workId()),
                row.chapterId() == null ? null : UUID.fromString(row.chapterId()));
        List<String> tags = request.tags() == null ? List.of() : request.tags();
        if (target.targetType() == TargetType.WORK) {
            if (DraftContentValidator.validate(target, request.title(), request.synopsis(),
                    parseUuid(request.categoryId(), "categoryId"), tags, null) != ErrorCode.OK) {
                throw new LuminBusinessException(ErrorCode.P1001);
            }
        } else {
            if (DraftContentValidator.validate(target, request.title(), null, null, null,
                    request.body()) != ErrorCode.OK) {
                throw new LuminBusinessException(ErrorCode.P1001);
            }
        }
        int rows = draftMapper.updateEditableContent(id, request.title(), request.synopsis(),
                request.categoryId(), toJson(tags), request.body(), subject,
                LocalDateTime.now(ZoneOffset.UTC), expected);
        if (rows == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        return new ReceiptView(id, "draft", "EDITABLE", row.lockVersion() + 1);
    }

    private DraftRow requireOwnedDraft(String subject, String id) {
        DraftRow row = id == null ? null : draftMapper.findById(id);
        if (row == null || row.deletedAt() != null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        workService.requireOwnedWork(row.workId(), subject);
        return row;
    }

    private DraftView toView(DraftRow row) {
        return new DraftView(row.id(), row.workId(), row.chapterId(), row.targetType(),
                row.title(), row.synopsis(), row.categoryId(), fromJson(row.tags()),
                row.coverAssetId(), row.bodyText(), row.state(), row.lockVersion());
    }

    private DraftContractView toContractView(DraftRow row) {
        return DraftContractView.of(row.id(), row.lockVersion(), row.state(),
                row.targetType(), row.workId(), row.chapterId(), row.baseVersionId(),
                row.title(), row.synopsis(), row.categoryId(), fromJson(row.tags()),
                row.coverAssetId(), row.bodyText());
    }

    private static String normalizeReason(String reason) {
        if (reason == null) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        String normalized = reason.trim();
        if (normalized.isEmpty() || normalized.length() > 500) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        return normalized;
    }

    private TargetRef targetOf(DraftCreateRequest request) {
        TargetType targetType = request.chapterId() == null
                ? TargetType.WORK : TargetType.CHAPTER;
        return new TargetRef(targetType, parseUuid(request.workId(), "workId"),
                parseUuid(request.chapterId(), "chapterId"));
    }

    /** If-Match：缺失 → 428/P1002；格式错 → 400/P1001（05 §2：带引号正整数）。 */
    private long parseIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new LuminBusinessException(ErrorCode.P1002);
        }
        String value = ifMatch.replace("\"", "");
        if (!value.matches("0|[1-9][0-9]*")) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        return Long.parseLong(value);
    }

    private UUID parseUuid(String value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
    }

    private String toJson(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("tags 序列化失败", e);
        }
    }

    private List<String> fromJson(String tags) {
        try {
            return objectMapper.readValue(tags, new TypeReference<List<String>>() {
            });
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("tags 反序列化失败", e);
        }
    }
}
