package com.leyon.lumin.catalog.service;

import com.leyon.lumin.catalog.repository.CurationItemRow;
import com.leyon.lumin.catalog.repository.CurationMapper;
import com.leyon.lumin.catalog.repository.CurationMapper.FeaturedListItem;
import com.leyon.lumin.catalog.repository.CurationMapper.CategoryHistoryItem;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.port.ProfileBandPort;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 运营精选/规则发现应用服务（docs/13 UGC-039 一阶段；V18 curation_item）：
 * 提案（OPERATOR）→ 独立审批（ADMIN，状态机 PENDING→ACTIVE/REJECTED）→
 * 撤位（ADMIN，ACTIVE→RETIRED）。曝光原因字典化；读者精选位带干预标识
 * （intervention=true 恒定——运营位永不伪装自然排序，禁止假自然付费榜）；
 * featured 可见性谓词与 /works 同源（不绕过适龄与版权）。
 * 分类历史读 content_version 不可变版本链（不另建表、不拼接修订字段）。
 */
@Service
public class CurationService {

    /** 曝光原因字典（ck_curation_reason 同源白名单）。 */
    public static final Set<String> EXPOSURE_REASONS =
            Set.of("FEATURED", "EDITOR_PICK", "CATEGORY_SPOTLIGHT");

    private static final Set<String> CURATION_OPERATORS = Set.of("OPERATOR");
    private static final Set<String> CURATION_APPROVERS = Set.of("ADMIN");
    private static final Set<String> CATEGORY_HISTORY_READERS = Set.of("OPERATOR", "ADMIN");
    private static final Set<String> EXPOSURE_STATES =
            Set.of("PENDING", "ACTIVE", "REJECTED", "RETIRED");

    private final CurationMapper curationMapper;
    private final ProfileBandPort profileBandPort;
    private final AuditRecorder auditRecorder;

    public CurationService(CurationMapper curationMapper, ProfileBandPort profileBandPort,
                           AuditRecorder auditRecorder) {
        this.curationMapper = curationMapper;
        this.profileBandPort = profileBandPort;
        this.auditRecorder = auditRecorder;
    }

    /** 提案运营精选（OPERATOR；作品须已发布；同规则版本同作品唯一）。 */
    @Transactional
    public CurationItemRow propose(String subject, String stubRole, String workId,
                                   String exposureReason, String note, Integer ruleVersion,
                                   LocalDateTime now) {
        requireRole(stubRole, CURATION_OPERATORS);
        if (exposureReason == null || !EXPOSURE_REASONS.contains(exposureReason)) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        if (note != null && note.length() > 200) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        int version = ruleVersion == null ? 1 : ruleVersion;
        if (version < 1 || version > 1_000_000) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        if (curationMapper.existsPublishedWork(workId) == 0) {
            // 未发布/不存在统一 404（05 §2 同文案不枚举）
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        CurationItemRow row = new CurationItemRow(UUID.randomUUID().toString(), version,
                workId, exposureReason, note, "PENDING", 0, subject, null,
                now, subject, now, subject, 1L);
        try {
            curationMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 同规则版本同作品重复提案 → 409
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, stubRole,
                "curation.proposed", "curation_item", workId,
                "提案运营精选：" + exposureReason, traceId(), now.toInstant(ZoneOffset.UTC)));
        return row;
    }

    /** 独立审批（ADMIN；PENDING→ACTIVE/REJECTED；乐观锁）。 */
    @Transactional
    public CurationItemRow review(String subject, String stubRole, String id,
                                  boolean approve, LocalDateTime now) {
        requireRole(stubRole, CURATION_APPROVERS);
        CurationItemRow row = requireRow(id);
        if (!"PENDING".equals(row.state())) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        String target = approve ? "ACTIVE" : "REJECTED";
        int rows = curationMapper.updateState(id, target, subject, now, subject,
                row.lockVersion());
        if (rows == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, stubRole,
                approve ? "curation.approved" : "curation.rejected", "curation_item",
                row.workId(), approve ? "精选生效" : "精选驳回", traceId(),
                now.toInstant(ZoneOffset.UTC)));
        return requireRow(id);
    }

    /** 撤位（ADMIN；ACTIVE→RETIRED）。 */
    @Transactional
    public CurationItemRow retire(String subject, String stubRole, String id,
                                  LocalDateTime now) {
        requireRole(stubRole, CURATION_APPROVERS);
        CurationItemRow row = requireRow(id);
        if (!"ACTIVE".equals(row.state())) {
            throw new LuminBusinessException(ErrorCode.B2001);
        }
        int rows = curationMapper.updateState(id, "RETIRED", row.reviewedBy(), now, subject,
                row.lockVersion());
        if (rows == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, stubRole,
                "curation.retired", "curation_item", row.workId(), "精选撤位",
                traceId(), now.toInstant(ZoneOffset.UTC)));
        return requireRow(id);
    }

    /**
     * 读者精选位（按主体年龄带同源可见性过滤；C4 按 C1；档案缺失按 C1
     * fail-closed）。每项带 exposureReason 与 intervention=true（干预显式）。
     */
    @Transactional(readOnly = true)
    public List<FeaturedListItem> featured(String subject) {
        String band = profileBandPort.effectiveAgeBand(subject);
        String bandVar = normalizedBandVar(band);
        return curationMapper.listActive(bandVar, 20);
    }

    /**
     * 精选单列表（OPERATOR/ADMIN；state 可选过滤——非法值 400；提案时间倒序）。
     * 工作台一阶段展示上限 50 条（分页随控制台深化批次）。
     */
    @Transactional(readOnly = true)
    public List<CurationItemRow> list(String stubRole, String state) {
        requireRole(stubRole, Set.of("OPERATOR", "ADMIN"));
        if (state != null && !state.isBlank() && !EXPOSURE_STATES.contains(state)) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        return curationMapper.listByState(
                state == null || state.isBlank() ? null : state, 50);
    }

    /** 分类历史（OPERATOR/ADMIN；版本不可变链，category 随版本）。 */
    @Transactional(readOnly = true)
    public List<CategoryHistoryItem> categoryHistory(String stubRole, String workId) {
        requireRole(stubRole, CATEGORY_HISTORY_READERS);
        return curationMapper.listCategoryHistory(workId);
    }

    private CurationItemRow requireRow(String id) {
        CurationItemRow row = id == null ? null : curationMapper.findById(id);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        return row;
    }

    private static void requireRole(String stubRole, Set<String> allowed) {
        if (stubRole == null || !allowed.contains(stubRole)) {
            throw new LuminBusinessException(ErrorCode.A2001);
        }
    }

    /** C4（未核实）按 C1 读带；C0–C3 原样；未知/缺失按 C1 fail-closed。 */
    private static String normalizedBandVar(String band) {
        if (band == null || band.isBlank()) {
            return "C1";
        }
        return "C4".equals(band) ? "C1" : band;
    }

    private static String traceId() {
        return com.leyon.lumin.foundation.contract.ApiEnvelope.currentTraceId();
    }
}
