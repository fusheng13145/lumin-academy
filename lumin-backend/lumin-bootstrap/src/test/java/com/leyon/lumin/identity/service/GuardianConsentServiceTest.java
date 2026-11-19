package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.ProposalReviewStore;
import com.leyon.lumin.foundation.crypto.FieldCipher;
import com.leyon.lumin.identity.port.GuardianVerificationPort;
import com.leyon.lumin.identity.repository.GuardianConsentMapper;
import com.leyon.lumin.identity.repository.GuardianConsentRow;
import com.leyon.lumin.identity.repository.RoleAssignmentMapper;
import com.leyon.lumin.identity.repository.UserProfileMapper;
import com.leyon.lumin.identity.repository.UserProfileRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GuardianConsentServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 12, 0);
    private static final String GUARDIAN = "00000000-0000-4000-8000-000000000001";
    private static final String CHILD = "00000000-0000-4000-8000-000000000002";
    private static final String CONSENT = "00000000-0000-4000-8000-000000000003";

    @Mock
    private GuardianConsentMapper consentMapper;
    @Mock
    private ProposalReviewStore reviewStore;
    @Mock
    private RoleAssignmentMapper assignmentMapper;
    @Mock
    private UserProfileMapper profileMapper;
    @Mock
    private GuardianVerificationPort verificationPort;

    private FieldCipher fieldCipher;
    private GuardianConsentService service;

    @BeforeEach
    void setUp() {
        fieldCipher = new FieldCipher("guardian-consent-unit-test-key");
        service = new GuardianConsentService(consentMapper, reviewStore, assignmentMapper,
                profileMapper, verificationPort, fieldCipher, "guardian-test-key-32-bytes");
        when(reviewStore.listByResource(anyString(), anyString())).thenReturn(List.of());
    }

    private UserProfileRow profile(String id, String verifiedBand) {
        return new UserProfileRow(id, "issuer", "subject", "昵称", null, verifiedBand,
                verifiedBand, verifiedBand, NOW, "ACTIVE", 1L, null, NOW, NOW, id, id, 1L, null);
    }

    private GuardianConsentRow consent(String guardian, String child, boolean revoked) {
        return new GuardianConsentRow(CONSENT, guardian, child, "digest",
                fieldCipher.encrypt("proof-ref", "guardian_consent.proof_ref"), "REGISTRATION",
                NOW, revoked ? NOW : null, NOW, NOW, guardian, guardian, 2L);
    }

    @Test
    void createRejectsNonGuardianAndInvalidShape() {
        assertCode(ErrorCode.A2001, () -> service.create(GUARDIAN, "AUTHOR", CHILD,
                "proof", "REGISTRATION", NOW));
        assertCode(ErrorCode.P1001, () -> service.create(GUARDIAN, "GUARDIAN", "not-uuid",
                "proof", "REGISTRATION", NOW));
        assertCode(ErrorCode.P1001, () -> service.create(GUARDIAN, "GUARDIAN", GUARDIAN,
                "proof", "REGISTRATION", NOW));
        assertCode(ErrorCode.P1001, () -> service.create(GUARDIAN, "GUARDIAN", CHILD,
                " ", "REGISTRATION", NOW));
        assertCode(ErrorCode.P1001, () -> service.create(GUARDIAN, "GUARDIAN", CHILD,
                null, "REGISTRATION", NOW));
        assertCode(ErrorCode.P1001, () -> service.create(GUARDIAN, "GUARDIAN", CHILD,
                "proof", null, NOW));
        assertCode(ErrorCode.P1001, () -> service.create(GUARDIAN, "GUARDIAN", CHILD,
                "proof", "NOPE", NOW));
    }

    @Test
    void createRejectsMissingOrIneligibleProfiles() {
        when(profileMapper.findById(GUARDIAN)).thenReturn(null);
        assertCode(ErrorCode.B2004, () -> service.create(GUARDIAN, "GUARDIAN", CHILD,
                "proof", "REGISTRATION", NOW));
        when(profileMapper.findById(GUARDIAN)).thenReturn(profile(GUARDIAN, "C2"));
        when(profileMapper.findById(CHILD)).thenReturn(profile(CHILD, "C2"));
        assertCode(ErrorCode.A2002, () -> service.create(GUARDIAN, "GUARDIAN", CHILD,
                "proof", "REGISTRATION", NOW));
        when(profileMapper.findById(GUARDIAN)).thenReturn(profile(GUARDIAN, "C3"));
        when(profileMapper.findById(CHILD)).thenReturn(profile(CHILD, "C3"));
        assertCode(ErrorCode.A2002, () -> service.create(GUARDIAN, "GUARDIAN", CHILD,
                "proof", "REGISTRATION", NOW));
        when(profileMapper.findById(CHILD)).thenReturn(profile(CHILD, "C2"));
        when(verificationPort.verify(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(false);
        assertCode(ErrorCode.A2002, () -> service.create(GUARDIAN, "GUARDIAN", CHILD,
                "proof", "REGISTRATION", NOW));
    }

    @Test
    void createRejectsDuplicateProofAndPersistsActiveConsent() {
        when(profileMapper.findById(GUARDIAN)).thenReturn(profile(GUARDIAN, "C3"));
        when(profileMapper.findById(CHILD)).thenReturn(profile(CHILD, "C2"));
        when(verificationPort.verify(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);
        when(consentMapper.findByProofDigest(anyString())).thenReturn(consent(GUARDIAN, CHILD, false));
        assertCode(ErrorCode.B2001, () -> service.create(GUARDIAN, "GUARDIAN", CHILD,
                "proof", "REGISTRATION", NOW));

        when(consentMapper.findByProofDigest(anyString())).thenReturn(null);
        GuardianConsentService.Receipt receipt = service.create(GUARDIAN, "GUARDIAN", CHILD,
                "proof", "REGISTRATION", NOW);
        assertEquals("ACTIVE", receipt.state());
        assertEquals("CONSENT", receipt.resourceType());
        verify(consentMapper).insert(any(GuardianConsentRow.class));
        verify(profileMapper).restoreAfterConsent(eq(CHILD), eq(NOW), eq(GUARDIAN));
    }

    @Test
    void listVisibleRejectsBadLimitAndPages() {
        assertCode(ErrorCode.P1001, () -> service.listVisible(GUARDIAN, null, 0, NOW));
        assertCode(ErrorCode.P1001, () -> service.listVisible(GUARDIAN, "bad", 10, NOW));
        when(consentMapper.listVisible(eq(GUARDIAN), any(), any(), eq(2)))
                .thenReturn(List.of(consent(GUARDIAN, CHILD, false)));
        assertEquals(1, service.listVisible(GUARDIAN, null, 1, NOW).items().size());
    }

    @Test
    void getVisibleEnforcesGuardianOrChildOnly() {
        assertCode(ErrorCode.B2004, () -> service.getVisible(GUARDIAN, null));
        when(consentMapper.findById(CONSENT)).thenReturn(consent(GUARDIAN, CHILD, false));
        assertCode(ErrorCode.B2004, () -> service.getVisible("00000000-0000-4000-8000-000000000009",
                CONSENT));
        assertEquals("ACTIVE", service.getVisible(GUARDIAN, CONSENT).state());
        assertEquals("ACTIVE", service.getVisible(CHILD, CONSENT).state());
    }

    @Test
    void revokeRejectsInvalidStateAndPersists() {
        assertCode(ErrorCode.A2001, () -> service.revoke(GUARDIAN, "AUTHOR", CONSENT,
                "撤销", 2, NOW));
        assertCode(ErrorCode.P1001, () -> service.revoke(GUARDIAN, "GUARDIAN", CONSENT,
                " ", 2, NOW));
        assertCode(ErrorCode.B2002, () -> service.revoke(GUARDIAN, "GUARDIAN", CONSENT,
                "撤销", 0, NOW));
        assertCode(ErrorCode.B2004, () -> service.revoke(GUARDIAN, "GUARDIAN", null,
                "撤销", 2, NOW));
        when(consentMapper.findById(CONSENT)).thenReturn(consent(GUARDIAN, CHILD, true));
        assertCode(ErrorCode.B2001, () -> service.revoke(GUARDIAN, "GUARDIAN", CONSENT,
                "撤销", 2, NOW));
        when(consentMapper.findById(CONSENT)).thenReturn(consent(GUARDIAN, CHILD, false));
        assertCode(ErrorCode.B2002, () -> service.revoke(GUARDIAN, "GUARDIAN", CONSENT,
                "撤销", 1, NOW));
        when(consentMapper.revoke(eq(CONSENT), eq(GUARDIAN), eq(2L), any(), eq(GUARDIAN)))
                .thenReturn(0);
        assertCode(ErrorCode.B2002, () -> service.revoke(GUARDIAN, "GUARDIAN", CONSENT,
                "撤销", 2, NOW));
        when(consentMapper.revoke(eq(CONSENT), eq(GUARDIAN), eq(2L), any(), eq(GUARDIAN)))
                .thenReturn(1);
        GuardianConsentService.Receipt receipt = service.revoke(GUARDIAN, "GUARDIAN", CONSENT,
                "撤销", 2, NOW);
        assertEquals("REVOKED", receipt.state());
        verify(profileMapper).downgradeAfterConsentRevocation(eq(CHILD), eq(NOW), eq(GUARDIAN));
    }

    @Test
    void createRequiresSubjectAndHandlesDuplicateInsertRace() {
        assertCode(ErrorCode.A0001, () -> service.create(null, "GUARDIAN", CHILD,
                "proof", "REGISTRATION", NOW));

        when(profileMapper.findById(GUARDIAN)).thenReturn(profile(GUARDIAN, "C3"));
        when(profileMapper.findById(CHILD)).thenReturn(profile(CHILD, "C2"));
        when(verificationPort.verify(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(true);
        when(consentMapper.findByProofDigest(anyString())).thenReturn(null);
        when(consentMapper.insert(any(GuardianConsentRow.class))).thenThrow(
                new org.springframework.dao.DuplicateKeyException("dup"));
        assertCode(ErrorCode.B2001, () -> service.create(GUARDIAN, "GUARDIAN", CHILD,
                "proof", "REGISTRATION", NOW));
    }

    @Test
    void getVisibleMapsCorruptCipherToDependencyFailure() {
        when(consentMapper.findById(CONSENT)).thenReturn(new GuardianConsentRow(CONSENT,
                GUARDIAN, CHILD, "digest", new byte[]{1, 2, 3}, "REGISTRATION",
                NOW, null, NOW, NOW, GUARDIAN, GUARDIAN, 2L));
        assertCode(ErrorCode.D2001, () -> service.getVisible(GUARDIAN, CONSENT));
    }

    @Test
    void revokeUsesDefaultLockVersionWhenRowLockIsNull() {
        when(consentMapper.findById(CONSENT)).thenReturn(new GuardianConsentRow(CONSENT,
                GUARDIAN, CHILD, "digest",
                fieldCipher.encrypt("proof-ref", "guardian_consent.proof_ref"), "REGISTRATION",
                NOW, null, NOW, NOW, GUARDIAN, GUARDIAN, null));
        when(consentMapper.revoke(eq(CONSENT), eq(GUARDIAN), eq(1L), any(), eq(GUARDIAN)))
                .thenReturn(1);

        GuardianConsentService.Receipt receipt = service.revoke(GUARDIAN, "GUARDIAN", CONSENT,
                "撤销", 1, NOW);
        assertEquals("REVOKED", receipt.state());
        assertEquals(2L, receipt.revision());
    }

    private static void assertCode(ErrorCode expected,
                                   org.junit.jupiter.api.function.Executable action) {
        LuminBusinessException error = assertThrows(LuminBusinessException.class, action);
        assertEquals(expected, error.code());
    }
}
