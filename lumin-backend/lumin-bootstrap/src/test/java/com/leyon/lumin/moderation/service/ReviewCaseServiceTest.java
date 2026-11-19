package com.leyon.lumin.moderation.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.cursor.CursorCodec;
import com.leyon.lumin.foundation.event.OutboxAppender;
import com.leyon.lumin.foundation.port.ContentMaterialPort;
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
}
