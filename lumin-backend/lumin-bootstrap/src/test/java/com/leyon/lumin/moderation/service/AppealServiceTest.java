package com.leyon.lumin.moderation.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.audit.ProposalReviewStore;
import com.leyon.lumin.foundation.port.CaseOwnershipPort;
import com.leyon.lumin.moderation.repository.AppealMapper;
import com.leyon.lumin.moderation.repository.AppealRow;
import com.leyon.lumin.moderation.repository.ReviewCaseMapper;
import com.leyon.lumin.moderation.repository.ReviewCaseRow;
import com.leyon.lumin.moderation.repository.ReviewDecisionActorMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AppealServiceTest {

    private static final String SUBJECT = "author-1";
    private static final String CASE = "case-1";
    private static final String APPEAL = "appeal-1";

    @Mock
    private AppealMapper appealMapper;
    @Mock
    private ReviewCaseMapper reviewCaseMapper;
    @Mock
    private ReviewDecisionActorMapper decisionActorMapper;
    @Mock
    private CaseOwnershipPort caseOwnershipPort;
    @Mock
    private AuditRecorder auditRecorder;
    @Mock
    private ProposalReviewStore reviewStore;

    private AppealService service;

    @BeforeEach
    void setUp() {
        service = new AppealService(appealMapper, reviewCaseMapper, decisionActorMapper,
                caseOwnershipPort, auditRecorder, reviewStore, new ObjectMapper());
    }

    private ReviewCaseRow reviewCase() {
        return new ReviewCaseRow(CASE, null, "workflow-1", "work-1", null, "SUBMISSION",
                "LOW", "P2", "DECIDED", "queue-1", 1, SUBJECT, null, LocalDateTime.now(),
                LocalDateTime.now(), 1L, LocalDateTime.now(), LocalDateTime.now(),
                SUBJECT, SUBJECT, 1L, null);
    }

    private AppealRow appeal(String state, long lock) {
        return new AppealRow(APPEAL, CASE, SUBJECT, "理由", "[]", null, state, null,
                LocalDateTime.now().plusHours(24), LocalDateTime.now().plusHours(72),
                LocalDateTime.now(), LocalDateTime.now(), SUBJECT, SUBJECT, lock);
    }

    @Test
    void createAppealRejectsInvalidShapeForeignCaseAndDuplicates() {
        assertCode(ErrorCode.P1001, () -> service.createAppeal(SUBJECT, null, "理由", List.of()));
        assertCode(ErrorCode.P1001, () -> service.createAppeal(SUBJECT, CASE, " ", List.of()));
        assertCode(ErrorCode.B2004, () -> service.createAppeal(SUBJECT, CASE, "理由", List.of()));
        when(reviewCaseMapper.findById(CASE)).thenReturn(reviewCase());
        when(caseOwnershipPort.authorIdOfWork("work-1")).thenReturn("other");
        assertCode(ErrorCode.B2004, () -> service.createAppeal(SUBJECT, CASE, "理由", List.of()));
        when(caseOwnershipPort.authorIdOfWork("work-1")).thenReturn(SUBJECT);
        when(appealMapper.findOpenByCaseId(CASE)).thenReturn(appeal("OPEN", 1));
        assertCode(ErrorCode.B2001, () -> service.createAppeal(SUBJECT, CASE, "理由", List.of()));
    }

    @Test
    void createAndGetAppealEnforceOwnership() {
        when(reviewCaseMapper.findById(CASE)).thenReturn(reviewCase());
        when(caseOwnershipPort.authorIdOfWork("work-1")).thenReturn(SUBJECT);
        when(appealMapper.findOpenByCaseId(CASE)).thenReturn(null);
        assertNotNull(service.createAppeal(SUBJECT, CASE, "理由", List.of("ev:1")));
        verify(appealMapper).insert(any(AppealRow.class));

        assertCode(ErrorCode.B2004, () -> service.getAppeal(SUBJECT, null));
        when(appealMapper.findById(APPEAL)).thenReturn(appeal("OPEN", 1));
        assertEquals("OPEN", service.getAppeal(SUBJECT, APPEAL).state());
        when(appealMapper.findById(APPEAL)).thenReturn(new AppealRow(APPEAL, CASE, "other",
                "理由", "[]", null, "OPEN", null, LocalDateTime.now(), LocalDateTime.now(),
                LocalDateTime.now(), LocalDateTime.now(), "other", "other", 1L));
        assertCode(ErrorCode.B2004, () -> service.getAppeal(SUBJECT, APPEAL));
    }

    @Test
    void reviewAppealRejectsInputConflictAndState() {
        assertCode(ErrorCode.P1001, () -> service.reviewAppeal("rev", APPEAL, "BAD", "理由", List.of()));
        assertCode(ErrorCode.P1001, () -> service.reviewAppeal("rev", APPEAL, "UPHOLD", " ", List.of()));
        assertCode(ErrorCode.B2004, () -> service.reviewAppeal("rev", APPEAL, "UPHOLD", "理由", List.of()));

        when(appealMapper.findById(APPEAL)).thenReturn(appeal("OPEN", 1));
        when(decisionActorMapper.findActorIdsByCaseId(CASE)).thenReturn(List.of("rev"));
        assertCode(ErrorCode.A2001, () -> service.reviewAppeal("rev", APPEAL, "UPHOLD", "理由", List.of()));

        when(decisionActorMapper.findActorIdsByCaseId(CASE)).thenReturn(List.of());
        when(appealMapper.findById(APPEAL)).thenReturn(appeal("UPHELD", 1));
        assertCode(ErrorCode.B2001, () -> service.reviewAppeal("rev", APPEAL, "UPHOLD", "理由", List.of()));
    }

    @Test
    void reviewAppealPersistsUpholdAndOverturn() {
        when(decisionActorMapper.findActorIdsByCaseId(CASE)).thenReturn(List.of());
        when(appealMapper.findById(APPEAL)).thenReturn(appeal("OPEN", 2));
        when(appealMapper.transition(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), any(), anyLong())).thenReturn(1);

        assertEquals("UPHELD", service.reviewAppeal("rev", APPEAL, "UPHOLD", "维持", List.of()));
        verify(reviewStore).append(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyList(), anyLong(), any());

        assertEquals("OVERTURNED", service.reviewAppeal("rev", APPEAL, "OVERTURN", "改判",
                List.of("ev:1")));
    }

    private static void assertCode(ErrorCode expected,
                                   org.junit.jupiter.api.function.Executable action) {
        LuminBusinessException error = assertThrows(LuminBusinessException.class, action);
        assertEquals(expected, error.code());
    }
}
