package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.crypto.FieldCipher;
import com.leyon.lumin.identity.port.GuardianVerificationPort;
import com.leyon.lumin.identity.repository.GuardianConsentMapper;
import com.leyon.lumin.identity.repository.GuardianConsentRow;
import com.leyon.lumin.foundation.audit.ProposalReviewStore;
import com.leyon.lumin.identity.repository.RoleAssignmentMapper;
import com.leyon.lumin.identity.repository.UserProfileMapper;
import com.leyon.lumin.identity.repository.UserProfileRow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * UGC-003 监护同意服务单元测试（不依赖真实 MySQL）：监护人须为已核验成年、
 * child 不得是成年、仅同意双方可读、撤销走乐观锁并立即降级。
 */
class UGC003GuardianConsentServiceTest {

    private static final String GUARDIAN = "6f8f8f8f-0000-4000-8000-00000000d007";
    private static final String CHILD = "6f8f8f8f-0000-4000-8000-00000000d003";
    private static final String OTHER = "6f8f8f8f-0000-4000-8000-00000000d006";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 0, 0);
    private static final String PROOF = "guardian-proof-1";

    private final GuardianConsentMapper consentMapper = mock(GuardianConsentMapper.class);
    private final ProposalReviewStore reviewStore = mock(ProposalReviewStore.class);
    private final RoleAssignmentMapper assignmentMapper = mock(RoleAssignmentMapper.class);
    private final UserProfileMapper profileMapper = mock(UserProfileMapper.class);
    private final GuardianVerificationPort port = mock(GuardianVerificationPort.class);
    private final FieldCipher cipher = new FieldCipher("unit-test-key");
    private final GuardianConsentService service = new GuardianConsentService(
            consentMapper, reviewStore, assignmentMapper, profileMapper, port, cipher,
            "unit-test-cursor-key");

    @Test
    void createRequiresVerifiedAdultGuardianAndMinorChild() {
        when(profileMapper.findById(GUARDIAN)).thenReturn(profile(GUARDIAN, "C3"));
        when(profileMapper.findById(CHILD)).thenReturn(profile(CHILD, "C2"));
        when(assignmentMapper.findActiveRoleCodes(GUARDIAN, NOW)).thenReturn(List.of("GUARDIAN"));
        when(port.verify(GUARDIAN, CHILD, "PUBLICATION", PROOF, NOW)).thenReturn(true);
        when(consentMapper.findByProofDigest(anyString())).thenReturn(null);
        when(consentMapper.insert(any())).thenReturn(1);
        when(profileMapper.restoreAfterConsent(CHILD, NOW, GUARDIAN)).thenReturn(1);

        GuardianConsentService.Receipt receipt = service.create(GUARDIAN, "GUARDIAN",
                CHILD, PROOF, "PUBLICATION", NOW);

        assertEquals("CONSENT", receipt.resourceType());
        assertEquals("ACTIVE", receipt.state());
        ArgumentCaptor<GuardianConsentRow> row = ArgumentCaptor.forClass(GuardianConsentRow.class);
        verify(consentMapper).insert(row.capture());
        assertThat(row.getValue().proofDigest()).hasSize(64);
        assertThat(new String(row.getValue().proofRefCipher(), StandardCharsets.UTF_8))
                .doesNotContain(PROOF);
        verify(profileMapper).restoreAfterConsent(CHILD, NOW, GUARDIAN);
    }

    @Test
    void unverifiedGuardianCannotCreate() {
        when(profileMapper.findById(GUARDIAN)).thenReturn(profile(GUARDIAN, "C4"));
        when(profileMapper.findById(CHILD)).thenReturn(profile(CHILD, "C2"));
        when(assignmentMapper.findActiveRoleCodes(GUARDIAN, NOW)).thenReturn(List.of("GUARDIAN"));

        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> service.create(GUARDIAN, "GUARDIAN", CHILD, PROOF, "PUBLICATION", NOW));
        assertEquals(ErrorCode.A2002, error.code());
        verify(port, never()).verify(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void onlyParticipantsCanRead() {
        GuardianConsentRow row = consentRow();
        when(consentMapper.findById(row.id())).thenReturn(row);
        when(reviewStore.listByResource("CONSENT", row.id())).thenReturn(List.of());

        GuardianConsentService.ConsentView view = service.getVisible(CHILD, row.id());
        assertEquals(PROOF, view.request().proofRef());
        assertEquals("ACTIVE", view.state());

        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> service.getVisible(OTHER, row.id()));
        assertEquals(ErrorCode.B2004, error.code());
    }

    @Test
    void revokeUsesOptimisticLockAndDowngradesMinor() {
        GuardianConsentRow row = consentRow();
        when(assignmentMapper.findActiveRoleCodes(GUARDIAN, NOW)).thenReturn(List.of("GUARDIAN"));
        when(consentMapper.findById(row.id())).thenReturn(row);
        when(consentMapper.revoke(row.id(), GUARDIAN, 1L, NOW, GUARDIAN)).thenReturn(1);
        when(profileMapper.downgradeAfterConsentRevocation(CHILD, NOW, GUARDIAN)).thenReturn(1);

        GuardianConsentService.Receipt receipt = service.revoke(GUARDIAN, "GUARDIAN",
                row.id(), "撤回同意", 1L, NOW);

        assertEquals("REVOKED", receipt.state());
        assertEquals(2L, receipt.revision());
        verify(profileMapper).downgradeAfterConsentRevocation(CHILD, NOW, GUARDIAN);
    }

    private GuardianConsentRow consentRow() {
        return new GuardianConsentRow("11111111-1111-4111-8111-111111111111", GUARDIAN, CHILD,
                "a".repeat(64), cipher.encrypt(PROOF, "guardian_consent.proof_ref"),
                "PUBLICATION", NOW, null, NOW, NOW, GUARDIAN, GUARDIAN, 1L);
    }

    private UserProfileRow profile(String id, String verifiedBand) {
        return new UserProfileRow(id, "urn:lumin:demo", id, "测试用户",
                cipher.encrypt("2000-01-01", "user_profile.birth_date"), "C3", verifiedBand,
                "C4".equals(verifiedBand) ? "C4" : verifiedBand, null, "ACTIVE", 1L, null,
                NOW, NOW, id, id, 1L, null);
    }
}
