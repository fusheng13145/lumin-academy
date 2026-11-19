package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.audit.ProposalReviewStore;
import com.leyon.lumin.identity.repository.RoleAssignmentMapper;
import com.leyon.lumin.identity.repository.RoleGrantMapper;
import com.leyon.lumin.identity.repository.RoleGrantRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RoleGrantServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 12, 0);
    private static final String ADMIN = "00000000-0000-4000-8000-000000000001";
    private static final String PROPOSER = "00000000-0000-4000-8000-000000000002";
    private static final String TARGET = "00000000-0000-4000-8000-000000000003";
    private static final String OTHER = "00000000-0000-4000-8000-000000000005";
    private static final String GRANT = "00000000-0000-4000-8000-000000000004";

    @Mock
    private RoleGrantMapper grantMapper;
    @Mock
    private ProposalReviewStore reviewStore;
    @Mock
    private RoleAssignmentMapper assignmentMapper;
    @Mock
    private AuditRecorder auditRecorder;

    private RoleGrantService service;

    @BeforeEach
    void setUp() {
        service = new RoleGrantService(grantMapper, reviewStore, assignmentMapper,
                auditRecorder, "role-grant-test-key-32-bytes");
        when(reviewStore.listByResource(anyString(), anyString())).thenReturn(List.of());
    }

    private RoleGrantRow row(String state, String requestedBy, String role) {
        return new RoleGrantRow(GRANT, TARGET, role, "GLOBAL", null, null, "理由",
                state, requestedBy, null, null, NOW, NOW, requestedBy, requestedBy, 2L);
    }

    @Test
    void proposeEnforcesRoleFieldsAndScopeShape() {
        assertCode(ErrorCode.A2001, () -> service.propose(PROPOSER, "AUTHOR", TARGET, "AUTHOR",
                "GLOBAL", null, null, "理由", NOW));
        assertCode(ErrorCode.P1001, () -> service.propose(PROPOSER, "OPERATOR", TARGET, "AUTHOR",
                "GLOBAL", null, null, " ", NOW));
        assertCode(ErrorCode.P1001, () -> service.propose(PROPOSER, "OPERATOR", TARGET, "NOPE",
                "GLOBAL", null, null, "理由", NOW));
        assertCode(ErrorCode.P1001, () -> service.propose(PROPOSER, "OPERATOR", TARGET, "AUTHOR",
                "GLOBAL", "scope-1", null, "理由", NOW));
        assertCode(ErrorCode.P1001, () -> service.propose(PROPOSER, "OPERATOR", TARGET, "AUTHOR",
                "WORK", null, null, "理由", NOW));
    }

    @Test
    void proposeAcceptsGlobalDefaultAndScopedGrant() {
        String global = service.propose(PROPOSER, "OPERATOR", TARGET, "AUTHOR",
                null, null, null, "理由", NOW);
        verify(grantMapper).insert(any(RoleGrantRow.class));
        assertNotNull(global);

        String scoped = service.propose(PROPOSER, "ADMIN", TARGET, "REVIEWER_L1",
                "WORK", "w1", null, "理由", NOW);
        assertNotNull(scoped);
    }

    @Test
    void reviewRequiresAdminAndValidInputs() {
        when(grantMapper.findActiveBySubject(eq(PROPOSER), any())).thenReturn(List.of());
        assertCode(ErrorCode.A2001, () -> service.review(PROPOSER, "AUTHOR", GRANT,
                true, NOW));
        assertCode(ErrorCode.P1001, () -> service.review(ADMIN, "ADMIN", GRANT,
                false, "x".repeat(1001), List.of(), NOW));
    }

    @Test
    void reviewRejectsMissingSelfAndIllegalTransition() {
        assertCode(ErrorCode.B2004, () -> service.review(ADMIN, "ADMIN", GRANT,
                true, "理由", List.of(), NOW));
        when(grantMapper.findByIdForUpdate(GRANT)).thenReturn(row("PENDING", ADMIN, "AUTHOR"));
        assertCode(ErrorCode.A2001, () -> service.review(ADMIN, "ADMIN", GRANT,
                true, "理由", List.of(), NOW));
        when(grantMapper.findByIdForUpdate(GRANT)).thenReturn(row("PENDING", PROPOSER, "NOPE"));
        assertCode(ErrorCode.P1001, () -> service.review(ADMIN, "ADMIN", GRANT,
                true, "理由", List.of(), NOW));
        when(grantMapper.findByIdForUpdate(GRANT)).thenReturn(row("PENDING", PROPOSER, "AUTHOR"));
        when(grantMapper.findActiveBySubject(eq(TARGET), any()))
                .thenReturn(List.of(row("APPROVED", PROPOSER, "REVIEWER_L1")));
        assertCode(ErrorCode.P1001, () -> service.review(ADMIN, "ADMIN", GRANT,
                true, "理由", List.of(), NOW));
    }

    @Test
    void reviewApprovesAndRejectsWithAuditAndBinding() {
        when(grantMapper.findByIdForUpdate(GRANT)).thenReturn(row("PENDING", PROPOSER, "AUTHOR"));
        when(grantMapper.review(eq(GRANT), eq("APPROVED"), eq(ADMIN), any(), eq(ADMIN),
                any(), eq(2L))).thenReturn(1);
        when(grantMapper.findActiveBySubject(eq(TARGET), any())).thenReturn(List.of());

        assertEquals("APPROVED", service.review(ADMIN, "ADMIN", GRANT, true,
                "通过", List.of("evidence"), NOW));
        verify(assignmentMapper).insertActive(anyString(), eq(TARGET), eq("AUTHOR"),
                eq("GLOBAL"), eq(null), eq(null), eq(GRANT), eq(NOW), eq(ADMIN));

        when(grantMapper.findByIdForUpdate(GRANT)).thenReturn(row("PENDING", PROPOSER, "AUTHOR"));
        when(grantMapper.review(eq(GRANT), eq("REJECTED"), eq(ADMIN), any(), eq(ADMIN),
                any(), eq(2L))).thenReturn(1);
        assertEquals("REJECTED", service.review(ADMIN, "ADMIN", GRANT, false,
                "驳回", List.of(), NOW));
    }

    @Test
    void reviewReportsOptimisticRace() {
        when(grantMapper.findByIdForUpdate(GRANT)).thenReturn(row("PENDING", PROPOSER, "AUTHOR"));
        when(grantMapper.review(eq(GRANT), eq("REJECTED"), eq(ADMIN), any(), eq(ADMIN),
                any(), eq(2L))).thenReturn(0);
        assertCode(ErrorCode.B2002, () -> service.review(ADMIN, "ADMIN", GRANT,
                false, "驳回", List.of(), NOW));
    }

    @Test
    void listVisibleRejectsLimitAndForeignCursor() {
        assertCode(ErrorCode.P1001, () -> service.listVisible(ADMIN, "ADMIN", null, 0, NOW));
        assertCode(ErrorCode.P1001, () -> service.listVisible(ADMIN, "ADMIN", "bad", 10, NOW));
    }

    @Test
    void listVisiblePagesAdminAndMapsExpiredState() {
        String first = "00000000-0000-4000-8000-0000000000a1";
        String second = "00000000-0000-4000-8000-0000000000a2";
        RoleGrantRow expired = new RoleGrantRow(first, TARGET, "AUTHOR", "GLOBAL", null,
                NOW.minusDays(1), "理由", "APPROVED", PROPOSER, ADMIN, NOW, NOW, NOW, PROPOSER,
                PROPOSER, 2L);
        RoleGrantRow active = new RoleGrantRow(second, TARGET, "AUTHOR", "GLOBAL", null,
                NOW.plusDays(1), "理由", "APPROVED", PROPOSER, ADMIN, NOW, NOW, NOW, PROPOSER,
                PROPOSER, 2L);
        when(grantMapper.listVisible(eq(ADMIN), eq(true), any(), any(), eq(2)))
                .thenReturn(List.of(expired, active));

        RoleGrantService.Page page = service.listVisible(ADMIN, "ADMIN", null, 1, NOW);

        assertEquals(1, page.items().size());
        assertEquals("EXPIRED", page.items().get(0).state());
        assertNotNull(page.nextCursor());
    }

    @Test
    void getVisibleEnforcesOwnershipAndExpiry() {
        assertCode(ErrorCode.B2004, () -> service.getVisible(ADMIN, "ADMIN", GRANT, NOW));
        when(grantMapper.findById(GRANT)).thenReturn(row("PENDING", PROPOSER, "AUTHOR"));
        assertCode(ErrorCode.B2004, () -> service.getVisible(OTHER, "AUTHOR", GRANT, NOW));

        when(grantMapper.findById(GRANT)).thenReturn(new RoleGrantRow(GRANT, TARGET, "AUTHOR",
                "GLOBAL", null, NOW.minusDays(1), "理由", "APPROVED", PROPOSER, ADMIN,
                NOW, NOW, NOW, PROPOSER, PROPOSER, 2L));
        assertEquals("EXPIRED", service.getVisible(TARGET, "AUTHOR", GRANT, NOW).state());
    }

    private static void assertCode(ErrorCode expected,
                                   org.junit.jupiter.api.function.Executable action) {
        LuminBusinessException error = assertThrows(LuminBusinessException.class, action);
        assertEquals(expected, error.code());
    }
}
