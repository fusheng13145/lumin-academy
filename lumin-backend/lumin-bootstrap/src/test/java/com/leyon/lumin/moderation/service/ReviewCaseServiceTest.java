package com.leyon.lumin.moderation.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.cursor.CursorCodec;
import com.leyon.lumin.foundation.event.OutboxAppender;
import com.leyon.lumin.foundation.port.ContentMaterialPort;
import com.leyon.lumin.foundation.port.ReviewerQueueScopePort;
import com.leyon.lumin.foundation.web.ReceiptView;
import com.leyon.lumin.moderation.api.dto.DecisionRequest;
import com.leyon.lumin.moderation.repository.ReviewCaseMapper;
import com.leyon.lumin.moderation.repository.ReviewCaseRow;
import com.leyon.lumin.moderation.repository.ReviewDecisionMapper;
import com.leyon.lumin.moderation.repository.ReviewDecisionRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReviewCaseServiceTest {

    private static final String SUBJECT = "7f7f7f7f-0000-4000-8000-0000000000c3";
    private static final String ROLE = "REVIEWER_L2";
    private static final String CURSOR_KEY = "test-cursor-key-0123456789abcdef";

    @Mock
    private ReviewCaseMapper caseMapper;
    @Mock
    private ReviewDecisionMapper decisionMapper;
    @Mock
    private OutboxAppender outboxAppender;
    @Mock
    private AuditRecorder auditRecorder;
    @Mock
    private ContentMaterialPort contentMaterialPort;
    @Mock
    private ReviewerQueueScopePort reviewerQueueScopePort;
    private ReviewCaseService service;

    @BeforeEach
    void setUp() {
        lenient().when(reviewerQueueScopePort.resolve(anyString(), anyString()))
                .thenReturn(ReviewerQueueScopePort.QueueScope.all());
        service = new ReviewCaseService(caseMapper, decisionMapper, outboxAppender,
                auditRecorder, mock(ObjectMapper.class), contentMaterialPort,
                reviewerQueueScopePort, CURSOR_KEY);
    }

    @Test
    void listCasesMapsContractPageAndIssuesSignedCursor() {
        LocalDateTime due = LocalDateTime.of(2026, 11, 19, 12, 0);
        String firstId = "00000000-0000-4000-8000-0000000000a1";
        when(caseMapper.listQueued(eq(SUBJECT), eq(false), eq(false), eq(true), isNull(),
                eq(0), any(LocalDateTime.class), anyString(), eq(2)))
                .thenReturn(List.of(
                        row(firstId, "P1", due),
                        row("00000000-0000-4000-8000-0000000000a2", "P2", due.plusHours(1))));
        when(decisionMapper.findByCaseIds(anyList())).thenReturn(List.of());

        ReviewCaseService.Page page = service.listCases(SUBJECT, ROLE, null, 1);

        assertEquals(1, page.items().size());
        assertEquals("C4", page.items().get(0).authorAgeBand());
        assertEquals("P1", page.items().get(0).priority());
        assertNotNull(page.nextCursor());
        CursorCodec.DecodedCursor decoded = CursorCodec.decode(page.nextCursor(),
                CURSOR_KEY.getBytes(StandardCharsets.UTF_8), Instant.now(),
                CursorCodec.filterDigest("list=review-cases;viewer=" + SUBJECT
                        + ";role=" + ROLE + ";queueScope=ALL"));
        assertEquals(ErrorCode.OK, decoded.code());
        assertEquals("2|" + due, decoded.payload().sortKeyValue());
        assertEquals(UUID.fromString(firstId), decoded.payload().lastId());
        verify(decisionMapper, times(1)).findByCaseIds(anyList());
    }

    @Test
    void listCasesMapsBatchedDecisionsToTheirOwnCase() {
        LocalDateTime due = LocalDateTime.of(2026, 11, 19, 12, 0);
        String firstId = "00000000-0000-4000-8000-0000000000b1";
        String secondId = "00000000-0000-4000-8000-0000000000b2";
        when(caseMapper.listQueued(eq(SUBJECT), eq(false), eq(false), eq(true), isNull(),
                eq(0), any(LocalDateTime.class), anyString(), eq(3)))
                .thenReturn(List.of(row(firstId, "P1", due), row(secondId, "P2", due)));
        when(decisionMapper.findByCaseIds(anyList())).thenReturn(List.of(
                decision("d-1", firstId), decision("d-2", secondId)));

        ReviewCaseService.Page page = service.listCases(SUBJECT, ROLE, null, 2);

        assertEquals(2, page.items().size());
        assertEquals(List.of("d-1"), page.items().get(0).decisions().stream()
                .map(com.leyon.lumin.moderation.api.dto.DecisionItemView::id).toList());
        assertEquals(List.of("d-2"), page.items().get(1).decisions().stream()
                .map(com.leyon.lumin.moderation.api.dto.DecisionItemView::id).toList());
        verify(decisionMapper, times(1)).findByCaseIds(anyList());
    }

    @Test
    void listCasesRejectsRoleLimitAndCursor() {
        LuminBusinessException roleError = assertThrows(LuminBusinessException.class,
                () -> service.listCases(SUBJECT, "AUTHOR", null, 10));
        assertEquals(ErrorCode.A2001, roleError.code());

        LuminBusinessException limitError = assertThrows(LuminBusinessException.class,
                () -> service.listCases(SUBJECT, ROLE, null, 0));
        assertEquals(ErrorCode.P1001, limitError.code());

        LuminBusinessException cursorError = assertThrows(LuminBusinessException.class,
                () -> service.listCases(SUBJECT, ROLE, "not-a-cursor", 10));
        assertEquals(ErrorCode.P1001, cursorError.code());
    }

    @Test
    void listCasesReadsAuthorAgeBandsInOneBatch() {
        LocalDateTime due = LocalDateTime.of(2026, 11, 19, 12, 0);
        String firstId = "00000000-0000-4000-8000-0000000000c1";
        String secondId = "00000000-0000-4000-8000-0000000000c2";
        String firstVersion = "11111111-1111-4111-8111-111111111111";
        String secondVersion = "22222222-2222-4222-8222-222222222222";
        when(caseMapper.listQueued(eq(SUBJECT), eq(false), eq(false), eq(true), isNull(),
                eq(0), any(LocalDateTime.class), anyString(), eq(3)))
                .thenReturn(List.of(row(firstId, "P1", due, firstVersion),
                        row(secondId, "P2", due, secondVersion)));
        when(decisionMapper.findByCaseIds(anyList())).thenReturn(List.of());
        when(contentMaterialPort.findVersionMaterials(anyList())).thenReturn(List.of(
                material(firstVersion, "C2"), material(secondVersion, "C3")));

        ReviewCaseService.Page page = service.listCases(SUBJECT, ROLE, null, 2);

        assertEquals("C2", page.items().get(0).authorAgeBand());
        assertEquals("C3", page.items().get(1).authorAgeBand());
        verify(contentMaterialPort, times(1)).findVersionMaterials(anyList());
        verify(contentMaterialPort, org.mockito.Mockito.never()).findVersionMaterial(anyString());
    }

    @Test
    void listCasesPassesQueueScopedIdsAndFailClosedWithoutScope() {
        when(reviewerQueueScopePort.resolve(SUBJECT, ROLE))
                .thenReturn(new ReviewerQueueScopePort.QueueScope(false,
                        Set.of("queue-b", "queue-a")));
        when(caseMapper.listQueued(eq(SUBJECT), eq(false), eq(false), eq(false),
                eq(List.of("queue-a", "queue-b")), eq(0), any(LocalDateTime.class),
                anyString(), eq(2))).thenReturn(List.of());

        ReviewCaseService.Page page = service.listCases(SUBJECT, ROLE, null, 1);

        assertEquals(0, page.items().size());
        verify(caseMapper).listQueued(eq(SUBJECT), eq(false), eq(false), eq(false),
                eq(List.of("queue-a", "queue-b")), eq(0), any(LocalDateTime.class),
                anyString(), eq(2));
    }

    @Test
    void listCasesReturnsEmptyPageWhenSessionHasNoQueueScope() {
        when(reviewerQueueScopePort.resolve(SUBJECT, ROLE))
                .thenReturn(ReviewerQueueScopePort.QueueScope.none());

        ReviewCaseService.Page page = service.listCases(SUBJECT, ROLE, null, 10);

        assertEquals(0, page.items().size());
        assertEquals(null, page.nextCursor());
        verifyNoInteractions(caseMapper);
    }

    private ContentMaterialPort.MaterialRef material(String versionId, String ageBand) {
        return new ContentMaterialPort.MaterialRef(versionId, "work-1", null, "WORK",
                "sha", 1L, ageBand);
    }

    private ReviewDecisionRow decision(String id, String caseId) {
        return new ReviewDecisionRow(id, caseId, SUBJECT, "HUMAN", "PASS", "G",
                "OK_1001", "合规", "[]", 1, 1L, LocalDateTime.now(),
                LocalDateTime.now(), SUBJECT, SUBJECT, 1L);
    }

    private ReviewCaseRow row(String id, String priority, LocalDateTime due) {
        return row(id, priority, due, null);
    }

    private ReviewCaseRow row(String id, String priority, LocalDateTime due, String versionId) {
        return new ReviewCaseRow(id, versionId, null, "work-1", null, "SUBMISSION",
                "LOW", priority, "QUEUED", "queue-1", 1, null, null, due, due,
                1L, due, due, SUBJECT, SUBJECT, 2L, null);
    }

    @Test
    void claimReturnsContractReceiptWithNextRevision() {
        LocalDateTime now = LocalDateTime.now();
        when(caseMapper.findByIdForUpdate("case-1")).thenReturn(new ReviewCaseRow(
                "case-1", null, null, "work-1", null, "SUBMISSION", "LOW", "P2",
                "QUEUED", "queue-1", 1, null, null, now.plusHours(4), now.plusHours(4),
                1L, now, now, SUBJECT, SUBJECT, 1L, null));
        when(caseMapper.claim(eq("case-1"), eq("CLAIMED"), eq(SUBJECT),
                any(LocalDateTime.class), eq(SUBJECT), any(LocalDateTime.class), eq(1L)))
                .thenReturn(1);

        ReceiptView receipt = service.claim(SUBJECT, ROLE, "case-1", "\"1\"");

        assertEquals("case-1", receipt.id());
        assertEquals("CASE", receipt.resourceType());
        assertEquals("CLAIMED", receipt.state());
        assertEquals(2L, receipt.revision());
    }

    @Test
    void decideReturnsContractReceiptForTerminalDecision() {
        LocalDateTime now = LocalDateTime.now();
        when(caseMapper.findByIdForUpdate("case-1")).thenReturn(new ReviewCaseRow(
                "case-1", null, null, "work-1", null, "SUBMISSION", "LOW", "P2",
                "CLAIMED", "queue-1", 1, SUBJECT, now.plusMinutes(10), now.plusHours(4),
                now.plusHours(4), 2L, now, now, SUBJECT, SUBJECT, 2L, null));
        when(caseMapper.transitionState(eq("case-1"), eq("DECIDED"), eq(1), eq("CLAIMED"),
                eq(SUBJECT), any(LocalDateTime.class), eq(2L))).thenReturn(1);

        ReceiptView receipt = service.decide(SUBJECT, ROLE, "case-1", "\"2\"",
                new DecisionRequest("PASS", "G", "OK_1001", "合规", List.of("ev:1")));

        assertEquals("case-1", receipt.id());
        assertEquals("CASE", receipt.resourceType());
        assertEquals("DECIDED", receipt.state());
        assertEquals(3L, receipt.revision());
    }
}
