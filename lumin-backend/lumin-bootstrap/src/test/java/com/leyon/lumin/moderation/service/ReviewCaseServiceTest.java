package com.leyon.lumin.moderation.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.cursor.CursorCodec;
import com.leyon.lumin.foundation.event.OutboxAppender;
import com.leyon.lumin.foundation.port.ContentMaterialPort;
import com.leyon.lumin.foundation.web.ReceiptView;
import com.leyon.lumin.moderation.api.dto.DecisionRequest;
import com.leyon.lumin.moderation.repository.ReviewCaseMapper;
import com.leyon.lumin.moderation.repository.ReviewCaseRow;
import com.leyon.lumin.moderation.repository.ReviewDecisionMapper;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    private ReviewCaseService service;

    @BeforeEach
    void setUp() {
        service = new ReviewCaseService(caseMapper, decisionMapper, outboxAppender,
                auditRecorder, mock(ObjectMapper.class), contentMaterialPort, CURSOR_KEY);
    }

    @Test
    void listCasesMapsContractPageAndIssuesSignedCursor() {
        LocalDateTime due = LocalDateTime.of(2026, 11, 19, 12, 0);
        String firstId = "00000000-0000-4000-8000-0000000000a1";
        when(caseMapper.listQueued(eq(0), any(LocalDateTime.class), anyString(), eq(2)))
                .thenReturn(List.of(
                        row(firstId, "P1", due),
                        row("00000000-0000-4000-8000-0000000000a2", "P2", due.plusHours(1))));
        when(decisionMapper.findByCase(anyString())).thenReturn(List.of());

        ReviewCaseService.Page page = service.listCases(SUBJECT, ROLE, null, 1);

        assertEquals(1, page.items().size());
        assertEquals("C4", page.items().get(0).authorAgeBand());
        assertEquals("P1", page.items().get(0).priority());
        assertNotNull(page.nextCursor());
        CursorCodec.DecodedCursor decoded = CursorCodec.decode(page.nextCursor(),
                CURSOR_KEY.getBytes(StandardCharsets.UTF_8), Instant.now(),
                CursorCodec.filterDigest("list=review-cases;viewer=" + SUBJECT + ";role=" + ROLE));
        assertEquals(ErrorCode.OK, decoded.code());
        assertEquals("2|" + due, decoded.payload().sortKeyValue());
        assertEquals(UUID.fromString(firstId), decoded.payload().lastId());
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

    private ReviewCaseRow row(String id, String priority, LocalDateTime due) {
        return new ReviewCaseRow(id, null, null, "work-1", null, "SUBMISSION",
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
