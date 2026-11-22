package com.leyon.lumin.catalog.service;

import com.leyon.lumin.catalog.repository.AssetUploadMapper;
import com.leyon.lumin.catalog.repository.AssetUploadRow;
import com.leyon.lumin.catalog.repository.ContentAssetMapper;
import com.leyon.lumin.catalog.repository.ContentAssetRow;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.crypto.FieldCipher;
import com.leyon.lumin.foundation.storage.ContentObjectStore;
import com.leyon.lumin.moderation.repository.ReviewCaseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssetUploadServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 12, 0);
    private static final String AUTHOR = "a1";
    private static final String WORK = "00000000-0000-4000-8000-000000000001";
    private static final String CASE = "00000000-0000-4000-8000-000000000002";
    private static final String SHA = "a".repeat(64);

    @Mock
    private AssetUploadMapper uploadMapper;
    @Mock
    private ContentAssetMapper assetMapper;
    @Mock
    private ContentObjectStore objectStore;
    @Mock
    private WorkService workService;
    @Mock
    private ReviewCaseMapper reviewCaseMapper;

    private AssetUploadService service;
    private FieldCipher fieldCipher;

    @BeforeEach
    void setUp() {
        fieldCipher = new FieldCipher("asset-upload-unit-test-key");
        service = new AssetUploadService(uploadMapper, assetMapper, objectStore,
                workService, fieldCipher, reviewCaseMapper);
    }

    private AssetUploadRow row(String state, String purpose, String contentType,
                               int size, String sha, String objectKey) {
        return new AssetUploadRow("u1", purpose, WORK, purpose.equals("EVIDENCE") ? CASE : null,
                contentType, size, sha, objectKey, state, "asset-1", null, AUTHOR,
                NOW.plusSeconds(300), NOW, NOW, AUTHOR, AUTHOR, 1L);
    }

    private static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void createRejectsInvalidShapeAndOwnership() {
        assertCode(ErrorCode.P1001, () -> service.create(AUTHOR, "COVER", WORK, CASE,
                "image/png", 10, SHA, NOW));
        assertCode(ErrorCode.P1001, () -> service.create(AUTHOR, "COVER", WORK, null,
                "image/gif", 10, SHA, NOW));
        assertCode(ErrorCode.P1001, () -> service.create(AUTHOR, "COVER", WORK, null,
                "image/png", 0, SHA, NOW));
        assertCode(ErrorCode.P1001, () -> service.create(AUTHOR, "COVER", WORK, null,
                "image/png", 10, "not-a-sha", NOW));
        when(workService.requireOwnedWork(WORK, AUTHOR))
                .thenThrow(new LuminBusinessException(ErrorCode.B2004));
        assertCode(ErrorCode.B2004, () -> service.create(AUTHOR, "COVER", WORK, null,
                "image/png", 10, SHA, NOW));
        assertCode(ErrorCode.P1001, () -> service.create(AUTHOR, "EVIDENCE", WORK, null,
                "image/png", 10, SHA, NOW));
    }

    /** 2026-11-20 安全审查回归：封面必须绑定**本人**作品，跨作者一律 404。 */
    @Test
    void createRejectsCoverBoundToForeignWork() {
        when(workService.requireOwnedWork(WORK, "intruder"))
                .thenThrow(new LuminBusinessException(ErrorCode.B2004));

        assertCode(ErrorCode.B2004, () -> service.create("intruder", "COVER", WORK, null,
                "image/png", 10, SHA, NOW));
    }

    @Test
    void createCoverIssuesSelfContainedSignedGrant() {
        when(workService.requireOwnedWork(WORK, AUTHOR)).thenReturn(
                new WorkService.WorkSummary(WORK, AUTHOR, "VALID", "ACTIVE", 1L));

        AssetUploadService.Grant grant = service.create(AUTHOR, "COVER", WORK, null,
                "image/png", 10, SHA, NOW);

        assertEquals("AWAITING_UPLOAD", grant.state());
        assertEquals(1L, grant.revision());
        AssetUploadService.GrantEnvelope envelope = service.verifyGrantSignature(grant.grantCipher());
        assertTrue(envelope.objectKey().startsWith("asset-upload/"));
        assertEquals(SHA, envelope.sha256());
        assertEquals(NOW.plusSeconds(300), envelope.expiresAt());
        verify(uploadMapper).insert(any(AssetUploadRow.class));
    }

    @Test
    void createEvidenceBindsCaseWithoutWork() {
        AssetUploadService.Grant grant = service.create(AUTHOR, "EVIDENCE", null, CASE,
                "image/png", 10, SHA, NOW);
        assertEquals("EVIDENCE", grant.purpose());
        assertEquals(CASE, grant.caseId());
    }

    @Test
    void verifyGrantRejectsTamperedCipher() {
        AssetUploadService.Grant grant = service.create(AUTHOR, "EVIDENCE", null, CASE,
                "image/png", 10, SHA, NOW);
        byte[] tampered = grant.grantCipher().clone();
        tampered[0] ^= 0x01;
        assertCode(ErrorCode.A0001, () -> service.verifyGrantSignature(tampered));
    }

    @Test
    void putRawObjectRejectsMissingStateSizeAndDigest() {
        when(uploadMapper.findByObjectKeyForUpdate("k1")).thenReturn(null);
        assertCode(ErrorCode.B2001, () -> service.putRawObject("k1", new byte[]{1}, NOW));
        when(uploadMapper.findByObjectKeyForUpdate("k1"))
                .thenReturn(row("COMPLETE", "COVER", "image/png", 1, SHA, "k1"));
        assertCode(ErrorCode.B2001, () -> service.putRawObject("k1", new byte[]{1}, NOW));
        when(uploadMapper.findByObjectKeyForUpdate("k1"))
                .thenReturn(row("AWAITING_UPLOAD", "COVER", "image/png", 2, SHA, "k1"));
        assertCode(ErrorCode.P1001, () -> service.putRawObject("k1", new byte[]{1}, NOW));
        when(uploadMapper.findByObjectKeyForUpdate("k1"))
                .thenReturn(row("AWAITING_UPLOAD", "COVER", "image/png", 2, SHA, "k1"));
        assertCode(ErrorCode.P1001, () -> service.putRawObject("k1", new byte[]{1, 2}, NOW));
    }

    @Test
    void putRawObjectStoresBytesAndMovesToChecking() {
        byte[] bytes = {1, 2};
        String actual = sha256Hex(bytes);
        when(uploadMapper.findByObjectKeyForUpdate("k1"))
                .thenReturn(row("AWAITING_UPLOAD", "COVER", "image/png", 2, actual, "k1"));
        when(uploadMapper.transition(anyString(), anyString(), any(), any(), anyString(),
                anyLong(), anyString(), any())).thenReturn(1);

        service.putRawObject("k1", bytes, NOW);

        verify(objectStore).putImmutable("k1", bytes);
        verify(uploadMapper).transition("u1", "CHECKING", null, null, "AWAITING_UPLOAD",
                1L, AUTHOR, NOW);
    }

    @Test
    void completeRejectsForeignOrFrozenUploadAndKeepsCheckingOnStorageFailure() {
        when(uploadMapper.findById("u1")).thenReturn(null);
        assertCode(ErrorCode.B2004, () -> service.complete(AUTHOR, "u1", NOW));
        when(uploadMapper.findById("u1"))
                .thenReturn(row("CHECKING", "COVER", "image/png", 10, SHA, "k1"));
        assertCode(ErrorCode.B2004, () -> service.complete("intruder", "u1", NOW));
        when(uploadMapper.findById("u1"))
                .thenReturn(row("AWAITING_UPLOAD", "COVER", "image/png", 10, SHA, "k1"));
        assertCode(ErrorCode.B2001, () -> service.complete(AUTHOR, "u1", NOW));
        when(uploadMapper.findById("u1"))
                .thenReturn(row("CHECKING", "COVER", "image/png", 10, SHA, "k1"));
        when(objectStore.read("k1")).thenThrow(new IllegalStateException("storage down"));
        assertCode(ErrorCode.D2001, () -> service.complete(AUTHOR, "u1", NOW));
    }

    @Test
    void completeIsIdempotentForCompletedUpload() {
        when(uploadMapper.findById("u1"))
                .thenReturn(row("COMPLETE", "COVER", "image/png", 10, SHA, "k1"));
        assertEquals("asset-1", service.complete(AUTHOR, "u1", NOW).assetId());
    }

    @Test
    void completeRejectsInvalidMagicWithoutIssuingAsset() throws Exception {
        when(uploadMapper.findById("u1"))
                .thenReturn(row("CHECKING", "COVER", "image/png", 3, SHA, "k1"));
        when(objectStore.read("k1")).thenReturn(new byte[]{1, 2, 3});
        when(uploadMapper.transition(anyString(), anyString(), any(), anyString(), anyString(),
                anyLong(), anyString(), any())).thenReturn(1);

        AssetUploadService.CompleteResult result = service.complete(AUTHOR, "u1", NOW);

        assertEquals("REJECTED", result.state());
        assertEquals(null, result.assetId());
        verify(uploadMapper).transition("u1", "REJECTED", null, "INVALID_MAGIC",
                "CHECKING", 1L, "system", NOW);
    }

    @Test
    void completeReencodesValidPngAndIssuesAsset() throws Exception {
        byte[] png = png();
        when(uploadMapper.findById("u1")).thenReturn(
                row("CHECKING", "COVER", "image/png", png.length, sha256Hex(png), "k1"));
        when(objectStore.read("k1")).thenReturn(png);
        when(uploadMapper.transition(anyString(), anyString(), anyString(), any(), anyString(),
                anyLong(), anyString(), any())).thenReturn(1);

        AssetUploadService.CompleteResult result = service.complete(AUTHOR, "u1", NOW);

        assertEquals("COMPLETE", result.state());
        assertNotNull(result.assetId());
        verify(objectStore).putImmutable(anyString(), any(byte[].class));
        verify(assetMapper).insert(any(ContentAssetRow.class));
    }

    private static void assertCode(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        LuminBusinessException error = assertThrows(LuminBusinessException.class, action);
        assertEquals(expected, error.code());
    }
}
