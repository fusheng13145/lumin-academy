package com.leyon.lumin.publication.service;

import com.leyon.lumin.catalog.service.WorkService;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.values.BusinessState;
import com.leyon.lumin.foundation.values.PublicationEvent;
import com.leyon.lumin.foundation.values.TargetType;
import com.leyon.lumin.foundation.web.IfMatch;
import com.leyon.lumin.foundation.web.ReceiptView;
import com.leyon.lumin.publication.domain.PublicationStateMachine;
import com.leyon.lumin.publication.domain.TransitionContext;
import com.leyon.lumin.publication.domain.TransitionResult;
import com.leyon.lumin.publication.repository.PublicationWorkflowMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 作者作品生命周期（05 §3.2 changeWorkLifecycle）。
 * COMPLETE 要求作品 ACTIVE、全部章节已发布、同目标无未结束流程；
 * REOPEN 要求 COMPLETED 且有原因；两者均经状态机裁决并使用 If-Match 乐观锁。
 */
@Service
public class WorkLifecycleService {

    private final WorkService workService;
    private final PublicationWorkflowMapper workflowMapper;

    public WorkLifecycleService(WorkService workService,
                                PublicationWorkflowMapper workflowMapper) {
        this.workService = workService;
        this.workflowMapper = workflowMapper;
    }

    public ReceiptView change(String subject, String workId, String action, String ifMatch,
                              String reason, LocalDateTime now) {
        String normalizedAction = normalizeAction(action);
        String normalizedReason = normalizeReason(reason);
        long expected = IfMatch.requireValid(ifMatch);
        WorkService.WorkSummary work = workService.requireOwnedWork(workId, subject);
        if (work.lockVersion() != expected) {
            throw new LuminBusinessException(ErrorCode.B2002);
        }
        BusinessState current = BusinessState.valueOf(work.businessState());
        PublicationEvent event = "COMPLETE".equals(normalizedAction)
                ? PublicationEvent.COMPLETE_REQUESTED : PublicationEvent.RESUME_SERIAL;
        boolean hasOpenWorkflow = workflowMapper.countOpenByWork(workId) > 0;
        TransitionContext context = new TransitionContext(TargetType.WORK, true,
                !hasOpenWorkflow, workService.allChaptersPublished(workId), normalizedReason,
                false, false, false, "VALID".equals(work.copyrightStatus()), true,
                true, true, true, true, false, true, false);
        TransitionResult result = PublicationStateMachine.business(current, event, context);
        if (!result.accepted()) {
            throw new LuminBusinessException(result.code());
        }
        long revision = workService.changeBusinessState(subject, workId,
                result.toState().name(), "COMPLETE".equals(normalizedAction), expected, now);
        return new ReceiptView(workId, "WORK", result.toState().name(), revision);
    }

    private static String normalizeAction(String action) {
        if (action == null) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        String normalized = action.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"COMPLETE".equals(normalized) && !"REOPEN".equals(normalized)) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        return normalized;
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
