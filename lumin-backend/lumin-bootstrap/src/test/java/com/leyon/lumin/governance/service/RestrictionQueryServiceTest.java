package com.leyon.lumin.governance.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.ProposalReviewStore;
import com.leyon.lumin.foundation.port.CaseOwnershipPort;
import com.leyon.lumin.governance.repository.RestrictionMapper;
import com.leyon.lumin.governance.repository.RestrictionRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** UGC-014 限制读取：授权过滤、过期态映射、完整审批历史。 */
class RestrictionQueryServiceTest {

    private static final String SUBJECT = "5e8f8f8f-0000-4000-8000-0000000000e1";
    private static final String OTHER = "4d8f8f8f-0000-4000-8000-0000000000f2";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 0, 0);

    private final RestrictionMapper restrictionMapper = mock(RestrictionMapper.class);
    private final ProposalReviewStore reviewStore = mock(ProposalReviewStore.class);
    private final CaseOwnershipPort ownershipPort = mock(CaseOwnershipPort.class);
    private final RestrictionQueryService service = new RestrictionQueryService(
            restrictionMapper, reviewStore, ownershipPort, "unit-test-key");

    @Test
    void listMapsExpiredTemporaryRestrictionAndReviews() {
        when(restrictionMapper.listVisible(eq(SUBJECT), eq(true), isNull(), isNull(), eq(11)))
                .thenReturn(List.of(row(NOW.minusDays(1))));
        when(reviewStore.listByResource("RESTRICTION", "restriction-1"))
                .thenReturn(List.of(review()));

        var page = service.listVisible(SUBJECT, "REVIEWER_L2", null, 10, NOW);

        assertEquals(1, page.items().size());
        assertEquals("EXPIRED", page.items().get(0).state());
        assertEquals(1, page.items().get(0).reviews().size());
        assertEquals("APPROVE", page.items().get(0).reviews().get(0).action());
    }

    @Test
    void detailAllowsTargetWorkAuthor() {
        when(restrictionMapper.findById("restriction-1")).thenReturn(row(NOW.plusDays(1)));
        when(ownershipPort.authorIdOfWork("work-1")).thenReturn(SUBJECT);
        when(reviewStore.listByResource("RESTRICTION", "restriction-1"))
                .thenReturn(List.of());

        var view = service.getVisible(SUBJECT, "AUTHOR", "restriction-1", NOW);

        assertEquals("restriction-1", view.id());
        assertEquals("TEMPORARY", view.request().kind());
    }

    @Test
    void detailRejectsStranger() {
        when(restrictionMapper.findById("restriction-1")).thenReturn(row(NOW.plusDays(1)));
        when(ownershipPort.authorIdOfWork("work-1")).thenReturn(OTHER);

        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> service.getVisible(SUBJECT, "AUTHOR", "restriction-1", NOW));

        assertEquals(ErrorCode.B2004, error.code());
    }

    private RestrictionRow row(LocalDateTime expiresAt) {
        return new RestrictionRow("restriction-1", "work-1", null, "TEMPORARY", "ACTIVE",
                "涉嫌违规", "case-1", OTHER, SUBJECT, NOW.minusDays(2), expiresAt, null,
                null, NOW.minusDays(2), NOW.minusDays(2), OTHER, SUBJECT, 2L);
    }

    private ProposalReviewStore.ReviewRecord review() {
        return new ProposalReviewStore.ReviewRecord("review-1", SUBJECT, "APPROVE",
                "复核生效", List.of("restriction:restriction-1"), 2L,
                NOW.minusDays(1));
    }
}
