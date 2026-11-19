package com.leyon.lumin.identity.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.crypto.FieldCipher;
import com.leyon.lumin.foundation.values.AgeBand;
import com.leyon.lumin.identity.port.AgeVerificationPort;
import com.leyon.lumin.identity.repository.AgeVerificationMapper;
import com.leyon.lumin.identity.repository.AgeVerificationRow;
import com.leyon.lumin.identity.repository.UserProfileMapper;
import com.leyon.lumin.identity.repository.UserProfileRow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

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
 * UGC-003 年龄核验服务单元测试（不依赖真实 MySQL）：验证成功才写档案、
 * 失败只留核验记录、C4/空 ageBand 不得宣称 VERIFIED、proofRef 不落明文。
 */
class UGC003AgeVerificationServiceTest {

    private static final String SUBJECT = "6f8f8f8f-0000-4000-8000-00000000d003";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 0, 0);
    private static final String BIRTH_AAD = "user_profile.birth_date";

    private final UserProfileMapper profileMapper = mock(UserProfileMapper.class);
    private final AgeVerificationMapper verificationMapper = mock(AgeVerificationMapper.class);
    private final AgeVerificationPort port = mock(AgeVerificationPort.class);
    private final FieldCipher cipher = new FieldCipher("unit-test-key");
    private final AgeVerificationService service =
            new AgeVerificationService(profileMapper, verificationMapper, port, cipher);

    @Test
    void verifiedAdultUpgradesAndStoresCipherNotPlaintext() {
        when(profileMapper.findById(SUBJECT)).thenReturn(profile("2000-01-01", "C3"));
        when(port.verify(anyString(), anyString(), any())).thenReturn(new AgeVerificationPort.Result(
                "test-fixture", "DOCUMENT", "VERIFIED", AgeBand.C3, NOW, NOW.plusYears(1)));
        when(verificationMapper.insert(any())).thenReturn(1);
        when(profileMapper.applyAgeVerification(anyString(), anyString(), anyString(),
                any(), any(), anyString())).thenReturn(1);

        AgeVerificationService.Receipt receipt = service.verify(SUBJECT, "adult-proof", NOW);

        assertEquals("VERIFIED", receipt.state());
        assertEquals("AGE_CHECK", receipt.resourceType());
        assertEquals(1L, receipt.revision());
        ArgumentCaptor<AgeVerificationRow> row = ArgumentCaptor.forClass(AgeVerificationRow.class);
        verify(verificationMapper).insert(row.capture());
        assertThat(row.getValue().proofDigest()).hasSize(64);
        assertThat(row.getValue().proofRefCipher()).isNotEmpty();
        assertThat(new String(row.getValue().proofRefCipher(), StandardCharsets.UTF_8))
                .doesNotContain("adult-proof");
        verify(profileMapper).applyAgeVerification(SUBJECT, "C3", "C3", NOW, NOW, SUBJECT);
    }

    @Test
    void rejectedVerificationDoesNotWriteProfile() {
        when(profileMapper.findById(SUBJECT)).thenReturn(profile("2000-01-01", "C3"));
        when(port.verify(anyString(), anyString(), any())).thenReturn(new AgeVerificationPort.Result(
                "test-fixture", "DOCUMENT", "REJECTED", null, NOW, NOW.plusYears(1)));
        when(verificationMapper.insert(any())).thenReturn(1);

        AgeVerificationService.Receipt receipt = service.verify(SUBJECT, "reject-proof", NOW);

        assertEquals("REJECTED", receipt.state());
        verify(profileMapper, never()).applyAgeVerification(anyString(), anyString(),
                anyString(), any(), any(), anyString());
    }

    @Test
    void verifiedCannotClaimC4OrMissingBand() {
        when(profileMapper.findById(SUBJECT)).thenReturn(profile("2000-01-01", "C3"));
        when(port.verify(anyString(), anyString(), any())).thenReturn(new AgeVerificationPort.Result(
                "test-fixture", "DOCUMENT", "VERIFIED", AgeBand.C4, NOW, NOW.plusYears(1)));

        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> service.verify(SUBJECT, "adult-proof", NOW));
        assertEquals(ErrorCode.D2001, error.code());
    }

    @Test
    void missingProfileIs404() {
        when(profileMapper.findById(SUBJECT)).thenReturn(null);
        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> service.verify(SUBJECT, "adult-proof", NOW));
        assertEquals(ErrorCode.B2004, error.code());
    }

    private UserProfileRow profile(String birthDate, String declaredBand) {
        return new UserProfileRow(SUBJECT, "urn:lumin:demo", SUBJECT, "测试读者",
                cipher.encrypt(birthDate, BIRTH_AAD), declaredBand, null, "C4", null,
                "ACTIVE", 1L, null, NOW, NOW, SUBJECT, SUBJECT, 1L, null);
    }
}
