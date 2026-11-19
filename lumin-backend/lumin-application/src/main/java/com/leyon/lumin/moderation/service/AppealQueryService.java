package com.leyon.lumin.moderation.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.cursor.CursorCodec;
import com.leyon.lumin.foundation.audit.ProposalReviewStore;
import com.leyon.lumin.foundation.port.ReviewerQueueScopePort;
import com.leyon.lumin.moderation.repository.AppealMapper;
import com.leyon.lumin.moderation.repository.AppealRow;
import com.leyon.lumin.moderation.repository.ReviewDecisionActorMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 申诉读取面（05 §3.3 listAppeals / getAppeal）。
 * 申请人及有效监护同意仅见本人相关申诉；独立 L2/LEGAL 复核队列排除申请人
 * 与原案全部裁决主体。先过滤后分页，游标绑定主体与过滤摘要。
 */
@Service
public class AppealQueryService {

    private static final Set<String> QUEUE_ROLES = Set.of("REVIEWER_L2", "LEGAL");

    private final AppealMapper appealMapper;
    private final ReviewDecisionActorMapper decisionActorMapper;
    private final ProposalReviewStore reviewStore;
    private final ReviewerQueueScopePort reviewerQueueScopePort;
    private final ObjectMapper objectMapper;
    private final String cursorKey;

    public AppealQueryService(AppealMapper appealMapper,
                              ReviewDecisionActorMapper decisionActorMapper,
                              ProposalReviewStore reviewStore,
                              ReviewerQueueScopePort reviewerQueueScopePort,
                              ObjectMapper objectMapper,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${lumin.cursor.key}") String cursorKey) {
        this.appealMapper = appealMapper;
        this.decisionActorMapper = decisionActorMapper;
        this.reviewStore = reviewStore;
        this.reviewerQueueScopePort = reviewerQueueScopePort;
        this.objectMapper = objectMapper;
        this.cursorKey = cursorKey;
    }

    /** 契约 AppealRequest。 */
    public record AppealRequestView(String caseId, String reason, List<String> evidenceRefs) {
    }

    /** 契约 ReviewRecord。 */
    public record ReviewView(String id, String actorId, String action, String reason,
                             List<String> evidenceRefs, long reviewedRevision,
                             String createdAt) {
    }

    /** 契约 AppealView。 */
    public record AppealView(String id, String caseId, String state, long revision,
                             String completionDueAt, String decisionReason,
                             String createdBy, String createdAt, AppealRequestView request,
                             List<ReviewView> reviews) {
    }

    /** 契约 AppealPage。 */
    public record Page(List<AppealView> items, String nextCursor) {
    }

    @Transactional(readOnly = true)
    public Page listVisible(String subject, String role, String cursorToken,
                            Integer limitParam, LocalDateTime now) {
        ErrorCode limitCheck = CursorCodec.validateLimit(limitParam);
        if (limitCheck != ErrorCode.OK) {
            throw new LuminBusinessException(limitCheck);
        }
        int limit = CursorCodec.resolveLimit(limitParam);
        boolean queueViewer = QUEUE_ROLES.contains(role);
        boolean legalViewer = queueViewer && "LEGAL".equals(role);
        ReviewerQueueScopePort.QueueScope scope = queueViewer
                ? reviewerQueueScopePort.resolve(subject, role) : null;
        String scopeKey = scope == null ? "N/A"
                : scope.global() ? "ALL"
                : String.join(",", scope.queueIds().stream().sorted().toList());
        String filterDigest = CursorCodec.filterDigest(
                "list=appeals;viewer=" + subject + ";queue=" + queueViewer
                        + ";scope=" + scopeKey);
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
            cursorAt = LocalDateTime.parse(decoded.payload().sortKeyValue());
            cursorId = decoded.payload().lastId().toString();
        }
        if (scope != null && !scope.global() && scope.queueIds().isEmpty()) {
            // session 模式无有效队列作用域：空页失败关闭，不泄露其他队列申诉
            return new Page(List.of(), null);
        }
        List<AppealRow> rows = appealMapper.listVisible(subject, queueViewer, legalViewer,
                scope == null || scope.global(), scope == null || scope.global() ? null
                        : scope.queueIds().stream().sorted().toList(),
                cursorAt, cursorId, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<AppealRow> page = rows.subList(0, Math.min(rows.size(), limit));
        List<AppealView> items = new ArrayList<>(page.size());
        for (AppealRow row : page) {
            items.add(toView(row));
        }
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            AppealRow last = page.get(page.size() - 1);
            nextCursor = CursorCodec.encode(new CursorCodec.Payload(
                    last.createdAt().toString(), UUID.fromString(last.id()),
                    filterDigest, UUID.fromString(subject),
                    now.toInstant(ZoneOffset.UTC)),
                    cursorKey.getBytes(StandardCharsets.UTF_8));
        }
        return new Page(items, nextCursor);
    }

    @Transactional(readOnly = true)
    public AppealView getVisible(String subject, String role, String appealId) {
        AppealRow row = appealId == null ? null : appealMapper.findById(appealId);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        boolean applicant = subject.equals(row.applicantId());
        boolean guardian = !applicant && appealMapper.existsEffectiveGuardianConsent(
                subject, row.applicantId());
        boolean queueViewer = QUEUE_ROLES.contains(role)
                && !applicant
                && !decisionActorMapper.findActorIdsByCaseId(row.caseId()).contains(subject);
        if (queueViewer) {
            ReviewerQueueScopePort.QueueScope scope =
                    reviewerQueueScopePort.resolve(subject, role);
            String queueId = scope.global() ? null : appealMapper.findQueueIdByCaseId(row.caseId());
            String source = "LEGAL".equals(role) ? appealMapper.findSourceByCaseId(row.caseId()) : null;
            queueViewer = (scope.global()
                    || (queueId != null && scope.queueIds().contains(queueId)))
                    && (!"LEGAL".equals(role) || "LEGAL".equals(source));
        }
        if (!applicant && !guardian && !queueViewer) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        return toView(row);
    }

    private AppealView toView(AppealRow row) {
        List<ReviewView> reviews = reviewStore.listByResource("APPEAL", row.id())
                .stream()
                .map(r -> new ReviewView(r.id(), r.actorId(), r.action(), r.reason(),
                        r.evidenceRefs(), r.reviewedRevision(), r.eventAt().toString()))
                .toList();
        AppealRequestView request = new AppealRequestView(row.caseId(), row.reason(),
                parseEvidence(row.evidenceRefs()));
        return new AppealView(row.id(), row.caseId(), row.state(), row.lockVersion(),
                row.completedDueAt().toString(), row.decisionReason(),
                row.applicantId(), row.createdAt().toString(), request, reviews);
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
