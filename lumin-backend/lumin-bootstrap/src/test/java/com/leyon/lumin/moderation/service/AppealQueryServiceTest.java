package com.leyon.lumin.moderation.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.ProposalReviewStore;
import com.leyon.lumin.moderation.repository.AppealMapper;
import com.leyon.lumin.moderation.repository.AppealRow;
import com.leyon.lumin.moderation.repository.ReviewDecisionActorMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** UGC-014 申诉读取：申请人/监护人可见、复核队列原审回避、统一 404。 */
class AppealQueryServiceTest {

    private static final String SUBJECT = "6f8f8f8f-0000-4000-8000-00000000d007";
    private static final String APPLICANT = "9f8f8f8f-0000-4000-8000-0000000000a1";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 0, 0);

    private final AppealMapper appealMapper = mock(AppealMapper.class);
    private final ReviewDecisionActorMapper actorMapper = mock(ReviewDecisionActorMapper.class);
    private final ProposalReviewStore reviewStore = mock(ProposalReviewStore.class);
    private final AppealQueryService service = new AppealQueryService(
            appealMapper, actorMapper, reviewStore, new ObjectMapper(), "unit-test-key");

    @Test
    void listApplicantAppealsReturnsRequestAndReviews() {
        when(appealMapper.listVisible(eq(SUBJECT), eq(false), isNull(), isNull(), eq(11)))
                .thenReturn(List.of(row()));
        when(reviewStore.listByResource("APPEAL", "appeal-1")).thenReturn(List.of());

        var page = service.listVisible(SUBJECT, "AUTHOR", null, 10, NOW);

        assertEquals(1, page.items().size());
        assertEquals("OPEN", page.items().get(0).state());
        assertEquals("case-1", page.items().get(0).request().caseId());
    }

    @Test
    void guardianCanReadChildAppeal() {
        when(appealMapper.findById("appeal-1")).thenReturn(row());
        when(appealMapper.existsEffectiveGuardianConsent(SUBJECT, APPLICANT)).thenReturn(true);

        var view = service.getVisible(SUBJECT, "GUARDIAN", "appeal-1");

        assertEquals("appeal-1", view.id());
    }

    @Test
    void queueReviewerRecusedFromOriginalCaseIsNotFound() {
        when(appealMapper.findById("appeal-1")).thenReturn(row());
        when(actorMapper.findActorIdsByCaseId("case-1")).thenReturn(List.of(SUBJECT));

        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> service.getVisible(SUBJECT, "REVIEWER_L2", "appeal-1"));

        assertEquals(ErrorCode.B2004, error.code());
    }

    private AppealRow row() {
        return new AppealRow("appeal-1", "case-1", APPLICANT, "对措施有异议",
                "[\"a:b\"]", null, "OPEN", null, NOW.plusHours(24), NOW.plusHours(72),
                NOW, NOW, APPLICANT, APPLICANT, 1L);
    }
}
