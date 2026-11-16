package com.leyon.lumin.authoring.service;

import com.leyon.lumin.authoring.domain.MaterialSnapshot;
import com.leyon.lumin.authoring.repository.ContentVersionMapper;
import com.leyon.lumin.authoring.repository.ContentVersionRow;
import com.leyon.lumin.authoring.repository.DraftMapper;
import com.leyon.lumin.authoring.repository.DraftRow;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.values.TargetType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 草稿生命周期服务（authoring 域所有权端口：draft 行锁、不可变版本创建、草稿态迁移）。
 * 提交编排由 publication 域经本端口完成（02 §3.1 允许边 publication→authoring），
 * 跨模块禁止直接访问 authoring Repository。本服务方法以 REQUIRED 传播并入调用方事务，
 * 保证版本/流程/Outbox/审计与草稿态同写同败（INV-06）。
 */
@Service
public class DraftLifecycleService {

    public record DraftSnapshot(String id, String workId, String chapterId, String targetType,
                                String title, String synopsis, String categoryId,
                                List<String> tags, String bodyText, String state,
                                long lockVersion) {
    }

    /** 不可变版本落库结果（materialSha256 供编排写 ContentSubmitted 事件载荷）。 */
    public record VersionPersisted(String versionId, String materialSha256) {
    }

    private final DraftMapper draftMapper;
    private final ContentVersionMapper contentVersionMapper;
    private final ObjectMapper objectMapper;

    public DraftLifecycleService(DraftMapper draftMapper,
                                 ContentVersionMapper contentVersionMapper,
                                 ObjectMapper objectMapper) {
        this.draftMapper = draftMapper;
        this.contentVersionMapper = contentVersionMapper;
        this.objectMapper = objectMapper;
    }

    /** 预检读取（非锁行）：不存在/已软删统一 404 B2004。 */
    public DraftSnapshot loadDraft(String draftId) {
        DraftRow row = draftId == null ? null : draftMapper.findById(draftId);
        if (row == null || row.deletedAt() != null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        return toSnapshot(row);
    }

    /**
     * 提交事务段：FOR UPDATE 锁行重校验 → 插入不可变版本（含材料快照与摘要，
     * 03 §4.1 第 8 段）→ draft=SUBMITTED（乐观锁兜底并发）。
     */
    @Transactional
    public VersionPersisted persistSubmission(DraftSnapshot draft, String versionId,
                                              String objectKey, String bodySha256,
                                              Integer byteLength, long policyVersion,
                                              long expectedLock, String operator,
                                              LocalDateTime now) {
        DraftRow locked = draftMapper.findByIdForUpdate(draft.id());
        if (locked == null || !"EDITABLE".equals(locked.state())) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        if (locked.lockVersion() != expectedLock) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        MaterialSnapshot snapshot = new MaterialSnapshot(
                TargetType.valueOf(draft.targetType()), UUID.fromString(draft.workId()),
                draft.chapterId() == null ? null : UUID.fromString(draft.chapterId()),
                draft.title(), draft.synopsis(),
                draft.categoryId() == null ? null : UUID.fromString(draft.categoryId()),
                draft.tags(), null, bodySha256);
        if (snapshot.validate() != ErrorCode.OK) {
            throw new LuminBusinessException(ErrorCode.D2002);
        }
        String materialSha256 = snapshot.materialSha256();
        // material_snapshot 列即规范化快照本体（03 §4.1 第 8 段固定键 JSON）
        String snapshotCanonicalJson =
                new String(snapshot.canonicalBytes(), StandardCharsets.UTF_8);
        contentVersionMapper.insert(new ContentVersionRow(versionId, draft.workId(),
                draft.chapterId(), draft.targetType(), 1L, draft.id(), draft.title(),
                draft.synopsis(), draft.categoryId(), toJson(draft.tags()), null,
                objectKey, bodySha256, snapshotCanonicalJson, materialSha256,
                byteLength, "SUBMITTED", null, policyVersion, now, now, operator,
                operator, 1L));
        if (draftMapper.submit(draft.id(), expectedLock, operator, now) == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        return new VersionPersisted(versionId, materialSha256);
    }

    private DraftSnapshot toSnapshot(DraftRow row) {
        return new DraftSnapshot(row.id(), row.workId(), row.chapterId(), row.targetType(),
                row.title(), row.synopsis(), row.categoryId(), parseTags(row.tags()),
                row.bodyText(), row.state(), row.lockVersion());
    }

    private List<String> parseTags(String tags) {
        try {
            return objectMapper.readValue(tags, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("tags 反序列化失败", e);
        }
    }

    private String toJson(List<String> tags) {
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (Exception e) {
            throw new IllegalStateException("tags 序列化失败", e);
        }
    }
}
