package com.leyon.lumin.governance.service;

import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.audit.ProposalReviewStore;
import com.leyon.lumin.foundation.port.PublicationGatePort;
import com.leyon.lumin.governance.repository.RestrictionMapper;
import com.leyon.lumin.governance.repository.RestrictionRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 临时限制到期：持久化 EXPIRED、进入复核告警、不自动公开。 */
class RestrictionExpiryServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 12, 0);

    private final RestrictionMapper restrictionMapper = mock(RestrictionMapper.class);
    private final AuditRecorder auditRecorder = mock(AuditRecorder.class);
    private final ProposalReviewStore reviewStore = mock(ProposalReviewStore.class);
    private final PublicationGatePort publicationGate = mock(PublicationGatePort.class);
    private final RestrictionService service = new RestrictionService(
            restrictionMapper, auditRecorder, reviewStore, publicationGate);

    @Test
    void expiresDueTemporaryRestrictionWithoutRestoringPublication() {
        RestrictionRow row = row("restriction-1", 2L);
        when(restrictionMapper.findDueTemporary(NOW, 100)).thenReturn(List.of(row));
        when(restrictionMapper.expire("restriction-1", NOW, 2L)).thenReturn(1);

        int expired = service.expireDueTemporary(NOW);

        assertEquals(1, expired);
        verify(auditRecorder).recordTransition(any(),
                eq(Map.of("restrictionState", "ACTIVE")),
                eq(Map.of("restrictionState", "EXPIRED")));
        // 03 §2.4：到期只退出生效集合，不调用发布恢复
        verify(publicationGate, never()).restore(any(), any(), any());
        verify(publicationGate, never()).takedown(any(), any(), any());
    }

    @Test
    void skipsConcurrentReviewAndContinuesNextRow() {
        RestrictionRow first = row("restriction-1", 2L);
        RestrictionRow second = row("restriction-2", 3L);
        when(restrictionMapper.findDueTemporary(NOW, 100))
                .thenReturn(List.of(first, second));
        when(restrictionMapper.expire("restriction-1", NOW, 2L)).thenReturn(0);
        when(restrictionMapper.expire("restriction-2", NOW, 3L)).thenReturn(1);

        int expired = service.expireDueTemporary(NOW);

        assertEquals(1, expired);
        verify(publicationGate, never()).restore(any(), any(), any());
    }

    private RestrictionRow row(String id, long lockVersion) {
        return new RestrictionRow(id, "work-1", null, "TEMPORARY", "ACTIVE",
                "临时措施", "case-1", "requester", "reviewer",
                NOW.minusDays(31), NOW.minusMinutes(1), null, null,
                NOW.minusDays(31), NOW.minusDays(31), "requester", "reviewer", lockVersion);
    }
}
