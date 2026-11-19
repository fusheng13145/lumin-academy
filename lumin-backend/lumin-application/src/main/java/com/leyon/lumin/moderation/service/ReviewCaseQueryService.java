package com.leyon.lumin.moderation.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.port.ContentMaterialPort;
import com.leyon.lumin.moderation.api.dto.CaseDetailView;
import com.leyon.lumin.moderation.api.dto.DecisionItemView;
import com.leyon.lumin.moderation.api.dto.ReviewContentView;
import com.leyon.lumin.moderation.repository.ReviewCaseMapper;
import com.leyon.lumin.moderation.repository.ReviewCaseRow;
import com.leyon.lumin.moderation.repository.ReviewDecisionMapper;
import com.leyon.lumin.moderation.repository.ReviewDecisionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

/**
 * 审核案件读取面（05 §3.3 getCase / getReviewContent）。
 *
 * <p>读取资格与队列列表一致：仅 REVIEWER_L1/L2/LEGAL；已被他人领取的案件
 * 不再向其他审核员暴露详情。详情只返回脱敏元数据与完整裁决记录；受控材料
 * 仅当前有效领取人可读，逐次核验案件状态、领取人、租约。
 * queue scope 与阶段 3 的完整矩阵仍待真实角色/队列链路接入（骨架登记）。
 */
@Service
public class ReviewCaseQueryService {

    private static final Set<String> CASE_READER_ROLES =
            Set.of("REVIEWER_L1", "REVIEWER_L2", "LEGAL");

    private final ReviewCaseMapper caseMapper;
    private final ReviewDecisionMapper decisionMapper;
    private final ContentMaterialPort contentMaterialPort;
    private final ObjectMapper objectMapper;

    public ReviewCaseQueryService(ReviewCaseMapper caseMapper,
                                  ReviewDecisionMapper decisionMapper,
                                  ContentMaterialPort contentMaterialPort,
                                  ObjectMapper objectMapper) {
        this.caseMapper = caseMapper;
        this.decisionMapper = decisionMapper;
        this.contentMaterialPort = contentMaterialPort;
        this.objectMapper = objectMapper;
    }

    /** 案件详情（脱敏元数据 + 完整裁决）；越权与不存在统一 404。 */
    @Transactional(readOnly = true)
    public CaseDetailView getCase(String subject, String role, String caseId) {
        ReviewCaseRow row = caseMapper.findById(caseId);
        requireReadable(subject, role, row);
        String authorAgeBand = "C4";
        if (row.versionId() != null) {
            ContentMaterialPort.MaterialRef material =
                    contentMaterialPort.findVersionMaterial(row.versionId());
            if (material != null && material.authorAgeBand() != null) {
                authorAgeBand = material.authorAgeBand();
            }
        }
        List<DecisionItemView> decisions = decisionMapper.findByCase(row.id()).stream()
                .map(this::toDecision)
                .toList();
        return new CaseDetailView(row.id(), row.workId(), row.versionId(), row.state(),
                row.risk(), row.priority(), authorAgeBand, row.completionDueAt(),
                row.leaseExpiresAt(), row.lockVersion(), decisions);
    }

    /** 受控材料：仅当前有效领取人；状态、领取人、租约任一不满足即 404。 */
    @Transactional(readOnly = true)
    public ReviewContentView getReviewContent(String subject, String role, String caseId) {
        ReviewCaseRow row = caseMapper.findById(caseId);
        requireReadable(subject, role, row);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        if (!Set.of("CLAIMED", "WAITING_SECOND").contains(row.state())
                || !subject.equals(row.assigneeId())
                || row.leaseExpiresAt() == null
                || !row.leaseExpiresAt().isAfter(now)) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        if (row.versionId() == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        ContentMaterialPort.ReviewMaterial material =
                contentMaterialPort.findReviewMaterial(row.versionId());
        if (material == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        String watermark = "lumin:" + subject + ":" + row.id();
        return new ReviewContentView(row.id(), material.title(),
                material.text() == null ? "" : material.text(), watermark, now);
    }

    private void requireReadable(String subject, String role, ReviewCaseRow row) {
        if (row == null || !CASE_READER_ROLES.contains(role)) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        if (row.assigneeId() != null && !row.assigneeId().equals(subject)
                && !"DECIDED".equals(row.state())) {
            // 已由他人领取的案件不再暴露给其他审核员；DECIDED 详情保留供裁决追溯
            throw new LuminBusinessException(ErrorCode.B2004);
        }
    }

    private DecisionItemView toDecision(ReviewDecisionRow row) {
        return new DecisionItemView(row.id(), row.actorId(), row.decision(), row.grade(),
                row.reasonCode(), row.reason(), parseEvidence(row.evidenceRefs()),
                row.createdAt());
    }

    private List<String> parseEvidence(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }
}
