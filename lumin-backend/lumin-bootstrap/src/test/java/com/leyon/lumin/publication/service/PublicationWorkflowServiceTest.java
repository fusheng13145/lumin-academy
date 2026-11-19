package com.leyon.lumin.publication.service;

import com.leyon.lumin.authoring.service.DraftLifecycleService;
import com.leyon.lumin.catalog.service.WorkService;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.cursor.CursorCodec;
import com.leyon.lumin.foundation.port.ReviewCaseDecisionPort;
import com.leyon.lumin.foundation.web.ReceiptView;
import com.leyon.lumin.publication.repository.PublicationPointerMapper;
import com.leyon.lumin.publication.repository.PublicationPointerRow;
import com.leyon.lumin.publication.repository.PublicationWorkflowMapper;
import com.leyon.lumin.publication.repository.PublicationWorkflowRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicationWorkflowServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 12, 0);
    private static final String SUBJECT = "00000000-0000-4000-8000-0000000000c1";

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

    @Test
    void listMyWorkflowsRejectsBadLimitAndForeignCursor() {
        assertCode(ErrorCode.P1001, () -> service.listMyWorkflows("s1", null, 0, NOW));
        assertCode(ErrorCode.P1001, () -> service.listMyWorkflows("s1", "not-a-cursor", 10, NOW));

        String digest = CursorCodec.filterDigest("list=my-publication-workflows;viewer=" + SUBJECT);
        String foreign = CursorCodec.encode(new CursorCodec.Payload(
                NOW.toString(), UUID.fromString("00000000-0000-4000-8000-0000000000aa"),
                digest, UUID.fromString("00000000-0000-4000-8000-0000000000bb"),
                Instant.now()), "test-cursor-key-32-bytes".getBytes(StandardCharsets.UTF_8));
        assertCode(ErrorCode.P1001, () -> service.listMyWorkflows(SUBJECT, foreign, 10, NOW));
    }

    @Test
    void listMyWorkflowsPagesAndMapsPublishedPointer() {
        PublicationWorkflowRow first = new PublicationWorkflowRow(
                "00000000-0000-4000-8000-0000000000a1", "v1", "WORK", "w1", "SUBMITTED",
                1L, "c1", null, NOW.minusMinutes(2), NOW.minusMinutes(2), "s1", "s1", 2L);
        PublicationWorkflowRow second = new PublicationWorkflowRow(
                "00000000-0000-4000-8000-0000000000a2", "v2", "WORK", "w2", "HUMAN_REVIEW",
                2L, "c2", null, NOW.minusMinutes(1), NOW.minusMinutes(1), "s1", "s1", 3L);
        when(workflowMapper.listByAuthor(eq(SUBJECT), any(), any(), eq(2)))
                .thenReturn(List.of(first, second));
        when(pointerMapper.findByTarget("WORK", "w1")).thenReturn(new PublicationPointerRow(
                "p1", "WORK", "w1", "v1", NOW, 1L, NOW, NOW, "s1", "s1", 1L));

        PublicationWorkflowService.Page page = service.listMyWorkflows(SUBJECT, null, 1, NOW);

        assertEquals(1, page.items().size());
        assertEquals("v1", page.items().get(0).publishedVersionId());
        assertNotNull(page.nextCursor());
    }

    @Test
    void cancelRejectsMissingRowInvalidIfMatchAndBlankReason() {
        assertCode(ErrorCode.P1001, () -> service.cancel("s1", "wf1", "\"2\"", " ", NOW));
        assertCode(ErrorCode.P1001, () -> service.cancel("s1", "wf1", "not-an-etag", "撤回", NOW));
        assertCode(ErrorCode.B2004, () -> service.cancel("s1", null, "\"2\"", "撤回", NOW));
    }

    @Test
    void cancelReportsOptimisticRaceOnWorkflowAndVersion() {
        when(workflowMapper.findById("wf1")).thenReturn(row("SUBMITTED", "c1", 2L));
        owned();
        when(decisionPort.hasEffectiveDecision("c1")).thenReturn(false);
        when(workflowMapper.cancel("wf1", 2L, "s1", NOW)).thenReturn(0);
        assertCode(ErrorCode.B2002, () -> service.cancel("s1", "wf1", "\"2\"", "撤回", NOW));

        when(workflowMapper.cancel("wf1", 2L, "s1", NOW)).thenReturn(1);
        when(draftLifecycleService.transitionVersionState("v1", "WITHDRAWN", null, "s1", NOW))
                .thenReturn(0);
        assertCode(ErrorCode.D2002, () -> service.cancel("s1", "wf1", "\"2\"", "撤回", NOW));
    }

    @Test
    void getVisibleRejectsMissingWorkflowAndMissingOwner() {
        assertCode(ErrorCode.B2004, () -> service.getVisible("s1", null));
        when(workflowMapper.findById("wf1")).thenReturn(row("SUBMITTED", "c1", 2L));
        when(draftLifecycleService.findVersionOwner("v1")).thenReturn(null);
        assertCode(ErrorCode.B2004, () -> service.getVisible("s1", "wf1"));
    }

    private static void assertCode(ErrorCode expected,
                                   org.junit.jupiter.api.function.Executable action) {
        LuminBusinessException error = assertThrows(LuminBusinessException.class, action);
        assertEquals(expected, error.code());
    }
}
