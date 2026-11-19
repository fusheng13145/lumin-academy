package com.leyon.lumin.moderation.service;

import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.audit.ProposalReviewStore;
import com.leyon.lumin.foundation.port.CaseOwnershipPort;
import com.leyon.lumin.moderation.repository.AppealMapper;
import com.leyon.lumin.moderation.repository.AppealRow;
import com.leyon.lumin.moderation.repository.ReviewCaseMapper;
import com.leyon.lumin.moderation.repository.ReviewDecisionActorMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 申诉复核写入 proposal_review 的 UPHOLD/OVERTURN 追加记录。 */
class AppealReviewHistoryTest {

    private static final String REVIEWER = "8f8f8f8f-0000-4000-8000-0000000000b2";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 0, 0);

    private final AppealMapper appealMapper = mock(AppealMapper.class);
    private final ReviewCaseMapper caseMapper = mock(ReviewCaseMapper.class);
    private final ReviewDecisionActorMapper actorMapper = mock(ReviewDecisionActorMapper.class);
    private final CaseOwnershipPort ownershipPort = mock(CaseOwnershipPort.class);
    private final AuditRecorder auditRecorder = mock(AuditRecorder.class);
    private final ProposalReviewStore reviewStore = mock(ProposalReviewStore.class);
    private final AppealService service = new AppealService(appealMapper, caseMapper,
            actorMapper, ownershipPort, auditRecorder, reviewStore, new ObjectMapper());

    @Test
    void reviewAppendsUpholdRecord() {
        when(appealMapper.findById("appeal-1")).thenReturn(new AppealRow("appeal-1",
                "case-1", "author-1", "有异议", "[\"a:b\"]", null, "OPEN", null,
                NOW.plusHours(24), NOW.plusHours(72), NOW, NOW, "author-1", "author-1", 1L));
        when(actorMapper.findActorIdsByCaseId("case-1")).thenReturn(List.of());
        when(appealMapper.transition(eq("appeal-1"), eq("UPHELD"), eq(REVIEWER),
                eq("维持原措施"), eq("OPEN"), eq(REVIEWER), any(LocalDateTime.class), eq(1L)))
                .thenReturn(1);

        String state = service.reviewAppeal(REVIEWER, "appeal-1", "UPHOLD",
                "维持原措施", List.of("a:b"));

        assertEquals("UPHELD", state);
        verify(reviewStore).append(eq("APPEAL"), eq("appeal-1"), eq(REVIEWER),
                eq("UPHOLD"), eq("维持原措施"), eq(List.of("a:b")), eq(1L),
                any(LocalDateTime.class));
    }
}
