package com.leyon.lumin.publication.service;

import com.leyon.lumin.authoring.service.DraftLifecycleService;
import com.leyon.lumin.catalog.service.WorkService;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.port.ReviewCaseDecisionPort;
import com.leyon.lumin.foundation.web.ReceiptView;
import com.leyon.lumin.publication.repository.PublicationPointerMapper;
import com.leyon.lumin.publication.repository.PublicationWorkflowMapper;
import com.leyon.lumin.publication.repository.PublicationWorkflowRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicationWorkflowServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 12, 0);

    @Mock
    private PublicationWorkflowMapper workflowMapper;
    @Mock
    private PublicationPointerMapper pointerMapper;
    @Mock
    private WorkService workService;
    @Mock
    private DraftLifecycleService draftLifecycleService;
    @Mock
    private ReviewCaseDecisionPort decisionPort;

    private PublicationWorkflowService service;

    @BeforeEach
    void setUp() {
        service = new PublicationWorkflowService(workflowMapper, pointerMapper, workService,
                draftLifecycleService, decisionPort, "test-cursor-key-32-bytes");
    }

    private PublicationWorkflowRow row(String state, String caseId, long lock) {
        return new PublicationWorkflowRow("wf1", "v1", "WORK", "w1", state, 1L, caseId,
                null, NOW.minusMinutes(1), NOW.minusMinutes(1), "s1", "s1", lock);
    }

    private void owned() {
        when(draftLifecycleService.findVersionOwner("v1"))
                .thenReturn(new DraftLifecycleService.VersionOwner("v1", "w1", null, "WORK"));
        when(workService.requireOwnedWork("w1", "s1"))
                .thenReturn(new WorkService.WorkSummary("w1", "s1", "VALID", "ACTIVE", 1L));
    }

    @Test
    void cancelSubmittedWorkflowMovesVersionToWithdrawn() {
        PublicationWorkflowRow row = row("SUBMITTED", "c1", 2L);
        when(workflowMapper.findById("wf1")).thenReturn(row);
        owned();
        when(decisionPort.hasEffectiveDecision("c1")).thenReturn(false);
        when(workflowMapper.cancel("wf1", 2L, "s1", NOW)).thenReturn(1);
        when(draftLifecycleService.transitionVersionState("v1", "WITHDRAWN", null, "s1", NOW))
                .thenReturn(1);

        ReceiptView receipt = service.cancel("s1", "wf1", "\"2\"", "撤回", NOW);

        assertEquals("CANCELLED", receipt.state());
        assertEquals("WORKFLOW", receipt.resourceType());
        assertEquals(3L, receipt.revision());
    }

    @Test
    void cancelRejectedWhenCaseAlreadyDecided() {
        when(workflowMapper.findById("wf1")).thenReturn(row("HUMAN_REVIEW", "c1", 2L));
        owned();
        when(decisionPort.hasEffectiveDecision("c1")).thenReturn(true);

        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> service.cancel("s1", "wf1", "\"2\"", "撤回", NOW));
        assertEquals(ErrorCode.B2001, error.code());
    }

    @Test
    void getWorkflowByOtherAuthorIsNotFound() {
        when(workflowMapper.findById("wf1")).thenReturn(row("SUBMITTED", "c1", 2L));
        when(draftLifecycleService.findVersionOwner("v1"))
                .thenReturn(new DraftLifecycleService.VersionOwner("v1", "w1", null, "WORK"));
        when(workService.requireOwnedWork("w1", "s2"))
                .thenThrow(new LuminBusinessException(ErrorCode.B2004));

        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> service.getVisible("s2", "wf1"));
        assertEquals(ErrorCode.B2004, error.code());
    }

    @Test
    void getWorkflowForOwnerReturnsStateAndRevision() {
        when(workflowMapper.findById("wf1")).thenReturn(row("SUBMITTED", "c1", 2L));
        owned();

        PublicationWorkflowService.WorkflowView view = service.getVisible("s1", "wf1");

        assertEquals("wf1", view.id());
        assertEquals("SUBMITTED", view.state());
        assertEquals(2L, view.revision());
    }

    @Test
    void staleIfMatchIsRejected() {
        when(workflowMapper.findById("wf1")).thenReturn(row("SUBMITTED", "c1", 2L));
        owned();

        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> service.cancel("s1", "wf1", "\"1\"", "撤回", NOW));
        assertEquals(ErrorCode.B2002, error.code());
    }
}
