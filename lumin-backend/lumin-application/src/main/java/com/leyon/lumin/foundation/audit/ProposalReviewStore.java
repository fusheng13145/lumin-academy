package com.leyon.lumin.foundation.audit;

import com.leyon.lumin.foundation.repository.ProposalReviewMapper;
import com.leyon.lumin.foundation.repository.ProposalReviewRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * proposal_review 跨域读写门面（docs/04 §5.2）。
 * 各提案域只追加不可覆写的审批记录；读取返回结构化记录，不向业务模块暴露
 * foundation Repository（02 §3.2 禁止跨模块直接访问 Repository）。
 */
@Service
public class ProposalReviewStore {

    private final ProposalReviewMapper reviewMapper;
    private final ObjectMapper objectMapper;

    public ProposalReviewStore(ProposalReviewMapper reviewMapper, ObjectMapper objectMapper) {
        this.reviewMapper = reviewMapper;
        this.objectMapper = objectMapper;
    }

    /** 契约 ReviewRecord 的领域表示。 */
    public record ReviewRecord(String id, String actorId, String action, String reason,
                               List<String> evidenceRefs, long reviewedRevision,
                               LocalDateTime eventAt) {
    }

    @Transactional
    public void append(String resourceType, String resourceId, String actorId, String action,
                       String reason, List<String> evidenceRefs, long reviewedRevision,
                       LocalDateTime now) {
        reviewMapper.insert(new ProposalReviewRow(UUID.randomUUID().toString(),
                resourceType, resourceId, actorId, action, reason,
                toJson(evidenceRefs), reviewedRevision, now, now, now, actorId, actorId, 1L));
    }

    @Transactional(readOnly = true)
    public List<ReviewRecord> listByResource(String resourceType, String resourceId) {
        return reviewMapper.listByResource(resourceType, resourceId).stream()
                .map(row -> new ReviewRecord(row.id(), row.actorId(), row.action(),
                        row.reason(), fromJson(row.evidenceRefsJson()),
                        row.reviewedRevision() == null ? 1L : row.reviewedRevision(),
                        row.eventAt()))
                .toList();
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (Exception e) {
            throw new IllegalStateException("proposal_review 证据序列化失败", e);
        }
    }

    private List<String> fromJson(String json) {
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
