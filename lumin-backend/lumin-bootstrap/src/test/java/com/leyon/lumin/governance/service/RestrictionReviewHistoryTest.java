package com.leyon.lumin.governance.service;

import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.audit.ProposalReviewStore;
import com.leyon.lumin.foundation.web.ReceiptView;
import com.leyon.lumin.governance.repository.RestrictionMapper;
import com.leyon.lumin.governance.repository.RestrictionRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 限制复核写入 proposal_review 的 APPROVE/REJECT 追加记录。 */
class RestrictionReviewHistoryTest {

    private static final String REVIEWER = "8f8f8f8f-0000-4000-8000-0000000000b2";
    private static final String REQUESTER = "5e8f8f8f-0000-4000-8000-0000000000e1";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 0, 0);

    private final RestrictionMapper restrictionMapper = mock(RestrictionMapper.class);
    private final AuditRecorder auditRecorder = mock(AuditRecorder.class);
    private final ProposalReviewStore reviewStore = mock(ProposalReviewStore.class);
    private final com.leyon.lumin.foundation.port.PublicationGatePort gatePort =
            mock(com.leyon.lumin.foundation.port.PublicationGatePort.class);
    private final RestrictionService service = new RestrictionService(restrictionMapper,
            auditRecorder, reviewStore, gatePort);

    @Test
    void reviewAppendsApproveRecord() {
        when(restrictionMapper.findById("restriction-1")).thenReturn(new RestrictionRow(
                "restriction-1", "work-1", null, "TEMPORARY", "PENDING", "涉嫌违规",
                "case-1", REQUESTER, null, null, NOW.plusDays(2), null, null,
                NOW, NOW, REQUESTER, REQUESTER, 2L));
        when(restrictionMapper.transitionState(eq("restriction-1"), eq("ACTIVE"),
                eq(REVIEWER), any(), eq("PENDING"), eq("system"), any(LocalDateTime.class),
                eq(2L))).thenReturn(1);

        ReceiptView receipt = service.reviewRestriction(REVIEWER, "restriction-1", true,
                "复核生效", List.of("restriction:restriction-1"), "\"2\"");

        assertEquals("restriction-1", receipt.id());
        assertEquals("RESTRICTION", receipt.resourceType());
        assertEquals("ACTIVE", receipt.state());
        assertEquals(3L, receipt.revision());
        verify(reviewStore).append(eq("RESTRICTION"), eq("restriction-1"), eq(REVIEWER),
                eq("APPROVE"), eq("复核生效"),
                eq(List.of("restriction:restriction-1")), eq(2L),
                any(LocalDateTime.class));
    }

    @Test
    void requestReturnsContractReceipt() {
        ReceiptView receipt = service.requestRestriction(REQUESTER,
                "11111111-1111-4111-8111-111111111111", null,
                "TEMPORARY", "涉嫌违规", NOW.plusDays(2),
                "22222222-2222-4222-8222-222222222222");

        assertEquals("RESTRICTION", receipt.resourceType());
        assertEquals("PENDING", receipt.state());
        assertEquals(1L, receipt.revision());
        verify(restrictionMapper).insert(any(RestrictionRow.class));
    }

    @Test
    void requestRejectsMissingCaseId() {
        org.junit.jupiter.api.Assertions.assertThrows(
                com.leyon.lumin.foundation.LuminBusinessException.class,
                () -> service.requestRestriction(REQUESTER,
                        "11111111-1111-4111-8111-111111111111", null,
                        "TEMPORARY", "涉嫌违规", NOW.plusDays(2), null));
        verify(restrictionMapper, org.mockito.Mockito.never())
                .insert(any(RestrictionRow.class));
    }
}
