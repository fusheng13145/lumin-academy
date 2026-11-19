package com.leyon.lumin.governance.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.cursor.CursorCodec;
import com.leyon.lumin.foundation.port.CaseOwnershipPort;
import com.leyon.lumin.foundation.audit.ProposalReviewStore;
import com.leyon.lumin.governance.repository.RestrictionMapper;
import com.leyon.lumin.governance.repository.RestrictionRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 限制读取面（05 §3.3 listRestrictions / getRestriction）。
 * 可见性 = 提案人 OR 目标作品作者 OR 授权作用域内的 L2/LEGAL/OPERATOR；
 * 先授权过滤再分页，游标绑定主体与过滤摘要。审批历史来自 proposal_review。
 */
@Service
public class RestrictionQueryService {

    private static final Set<String> QUEUE_ROLES = Set.of("REVIEWER_L2", "LEGAL", "OPERATOR");

    private final RestrictionMapper restrictionMapper;
    private final ProposalReviewStore reviewStore;
    private final CaseOwnershipPort caseOwnershipPort;
    private final String cursorKey;

    public RestrictionQueryService(RestrictionMapper restrictionMapper,
                                   ProposalReviewStore reviewStore,
                                   CaseOwnershipPort caseOwnershipPort,
                                   @org.springframework.beans.factory.annotation.Value(
                                           "${lumin.cursor.key}") String cursorKey) {
        this.restrictionMapper = restrictionMapper;
        this.reviewStore = reviewStore;
        this.caseOwnershipPort = caseOwnershipPort;
        this.cursorKey = cursorKey;
    }

    /** 契约 RestrictionRequest。 */
    public record RestrictionRequestView(String workId, String chapterId, String caseId,
                                         String kind, String expiresAt, String reason) {
    }

    /** 契约 ReviewRecord。 */
    public record ReviewView(String id, String actorId, String action, String reason,
                             List<String> evidenceRefs, long reviewedRevision,
                             String createdAt) {
    }

    /** 契约 RestrictionView。 */
    public record RestrictionView(String id, String createdBy, String createdAt,
                                  RestrictionRequestView request, long revision, String state,
                                  List<ReviewView> reviews) {
    }

    /** 契约 RestrictionPage。 */
    public record Page(List<RestrictionView> items, String nextCursor) {
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
        String filterDigest = CursorCodec.filterDigest(
                "list=restrictions;viewer=" + subject + ";queue=" + queueViewer);
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
        List<RestrictionRow> rows = restrictionMapper.listVisible(subject, queueViewer,
                cursorAt, cursorId, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<RestrictionRow> page = rows.subList(0, Math.min(rows.size(), limit));
        List<RestrictionView> items = new ArrayList<>(page.size());
        for (RestrictionRow row : page) {
            items.add(toView(row, now));
        }
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            RestrictionRow last = page.get(page.size() - 1);
            nextCursor = CursorCodec.encode(new CursorCodec.Payload(
                    last.createdAt().toString(), UUID.fromString(last.id()),
                    filterDigest, UUID.fromString(subject),
                    now.toInstant(ZoneOffset.UTC)),
                    cursorKey.getBytes(StandardCharsets.UTF_8));
        }
        return new Page(items, nextCursor);
    }

    @Transactional(readOnly = true)
    public RestrictionView getVisible(String subject, String role, String restrictionId,
                                      LocalDateTime now) {
        RestrictionRow row = restrictionId == null ? null
                : restrictionMapper.findById(restrictionId);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        boolean queueViewer = QUEUE_ROLES.contains(role);
        boolean owner = subject.equals(row.requestedBy());
        boolean workAuthor = subject.equals(caseOwnershipPort.authorIdOfWork(row.workId()));
        if (!queueViewer && !owner && !workAuthor) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        return toView(row, now);
    }

    private RestrictionView toView(RestrictionRow row, LocalDateTime now) {
        String state = row.state();
        if ("ACTIVE".equals(state) && "TEMPORARY".equals(row.kind())
                && row.expiresAt() != null && !row.expiresAt().isAfter(now)) {
            state = "EXPIRED";
        }
        List<ReviewView> reviews = reviewStore.listByResource("RESTRICTION", row.id())
                .stream()
                .map(r -> new ReviewView(r.id(), r.actorId(), r.action(), r.reason(),
                        r.evidenceRefs(), r.reviewedRevision(), r.eventAt().toString()))
                .toList();
        RestrictionRequestView request = new RestrictionRequestView(row.workId(),
                row.chapterId(), row.caseId(), row.kind(),
                row.expiresAt() == null ? null : row.expiresAt().toString(), row.reason());
        return new RestrictionView(row.id(), row.requestedBy(), row.createdAt().toString(),
                request, row.lockVersion(), state, reviews);
    }
}
