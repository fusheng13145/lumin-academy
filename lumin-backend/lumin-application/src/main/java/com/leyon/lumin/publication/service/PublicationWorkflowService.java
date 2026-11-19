package com.leyon.lumin.publication.service;

import com.leyon.lumin.authoring.service.DraftLifecycleService;
import com.leyon.lumin.catalog.service.WorkService;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.cursor.CursorCodec;
import com.leyon.lumin.foundation.port.ReviewCaseDecisionPort;
import com.leyon.lumin.foundation.values.PublicationEvent;
import com.leyon.lumin.foundation.values.TargetType;
import com.leyon.lumin.foundation.values.WorkflowState;
import com.leyon.lumin.foundation.web.IfMatch;
import com.leyon.lumin.foundation.web.ReceiptView;
import com.leyon.lumin.publication.domain.PublicationStateMachine;
import com.leyon.lumin.publication.domain.TransitionContext;
import com.leyon.lumin.publication.domain.TransitionResult;
import com.leyon.lumin.publication.repository.PublicationPointerMapper;
import com.leyon.lumin.publication.repository.PublicationPointerRow;
import com.leyon.lumin.publication.repository.PublicationWorkflowMapper;
import com.leyon.lumin.publication.repository.PublicationWorkflowRow;
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
 * 发布流程读取与作者撤回（05 §3.2 listMyPublicationWorkflows / getWorkflow / cancelWorkflow）。
 * 列表按作品归属过滤；详情/撤回仅作品作者；撤回经状态机 WITHDRAW，版本同步 WITHDRAWN。
 */
@Service
public class PublicationWorkflowService {

    private final PublicationWorkflowMapper workflowMapper;
    private final PublicationPointerMapper pointerMapper;
    private final WorkService workService;
    private final DraftLifecycleService draftLifecycleService;
    private final ReviewCaseDecisionPort decisionPort;
    private final String cursorKey;

    public PublicationWorkflowService(PublicationWorkflowMapper workflowMapper,
                                      PublicationPointerMapper pointerMapper,
                                      WorkService workService,
                                      DraftLifecycleService draftLifecycleService,
                                      ReviewCaseDecisionPort decisionPort,
                                      @Value("${lumin.cursor.key}") String cursorKey) {
        this.workflowMapper = workflowMapper;
        this.pointerMapper = pointerMapper;
        this.workService = workService;
        this.draftLifecycleService = draftLifecycleService;
        this.decisionPort = decisionPort;
        this.cursorKey = cursorKey;
    }

    /** 契约 WorkflowView（05 §3.2）。 */
    public record WorkflowView(String id, String versionId, String state, long revision,
                               String publishedVersionId, String suspendedReason) {
    }

    /** 契约 WorkflowPage。 */
    public record Page(List<WorkflowView> items, String nextCursor) {
    }

    @Transactional(readOnly = true)
    public Page listMyWorkflows(String subject, String cursorToken, Integer limitParam,
                                LocalDateTime now) {
        ErrorCode limitCheck = CursorCodec.validateLimit(limitParam);
        if (limitCheck != ErrorCode.OK) {
            throw new LuminBusinessException(limitCheck);
        }
        int limit = CursorCodec.resolveLimit(limitParam);
        String filterDigest = CursorCodec.filterDigest(
                "list=my-publication-workflows;viewer=" + subject);
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
        List<PublicationWorkflowRow> rows = workflowMapper.listByAuthor(subject, cursorAt,
                cursorId, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<PublicationWorkflowRow> page = rows.subList(0, Math.min(rows.size(), limit));
        List<WorkflowView> items = new ArrayList<>(page.size());
        for (PublicationWorkflowRow row : page) {
            items.add(toView(row));
        }
        String nextCursor = null;
        if (hasMore && !page.isEmpty()) {
            PublicationWorkflowRow last = page.get(page.size() - 1);
            nextCursor = CursorCodec.encode(new CursorCodec.Payload(
                    last.createdAt().toString(), UUID.fromString(last.id()), filterDigest,
                    UUID.fromString(subject), now.toInstant(ZoneOffset.UTC)),
                    cursorKey.getBytes(StandardCharsets.UTF_8));
        }
        return new Page(items, nextCursor);
    }

    @Transactional(readOnly = true)
    public WorkflowView getVisible(String subject, String workflowId) {
        PublicationWorkflowRow row = workflowId == null ? null
                : workflowMapper.findById(workflowId);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        requireOwned(row, subject);
        return toView(row);
    }

    @Transactional
    public ReceiptView cancel(String subject, String workflowId, String ifMatch, String reason,
                              LocalDateTime now) {
        String normalizedReason = normalizeReason(reason);
        long expected = IfMatch.requireValid(ifMatch);
        PublicationWorkflowRow row = workflowId == null ? null
                : workflowMapper.findById(workflowId);
        if (row == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        requireOwned(row, subject);
        if (row.lockVersion() != expected) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        WorkflowState current = WorkflowState.valueOf(row.state());
        boolean hasDecision = decisionPort.hasEffectiveDecision(row.caseId());
        TransitionContext context = new TransitionContext(TargetType.valueOf(row.targetType()),
                true, false, false, normalizedReason, false, false, false, false, true,
                true, false, !hasDecision, true, false, true, false);
        TransitionResult result = PublicationStateMachine.workflow(current,
                PublicationEvent.WITHDRAW, context);
        if (!result.accepted()) {
            throw new LuminBusinessException(result.code());
        }
        int updated = workflowMapper.cancel(workflowId, expected, subject, now);
        if (updated == 0) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        int versionUpdated = draftLifecycleService.transitionVersionState(row.versionId(),
                "WITHDRAWN", null, subject, now);
        if (versionUpdated == 0) {
            throw new LuminBusinessException(ErrorCode.D2002);
        }
        return new ReceiptView(workflowId, "WORKFLOW", "CANCELLED", expected + 1);
    }

    private void requireOwned(PublicationWorkflowRow row, String subject) {
        DraftLifecycleService.VersionOwner owner =
                draftLifecycleService.findVersionOwner(row.versionId());
        if (owner == null) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        workService.requireOwnedWork(owner.workId(), subject);
    }

    private WorkflowView toView(PublicationWorkflowRow row) {
        PublicationPointerRow pointer = pointerMapper.findByTarget(row.targetType(),
                row.targetId());
        String publishedVersionId = pointer == null ? null : pointer.versionId();
        return new WorkflowView(row.id(), row.versionId(), row.state(),
                row.lockVersion() == null ? 1L : row.lockVersion(), publishedVersionId,
                row.suspendedReason());
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
}
