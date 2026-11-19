package com.leyon.lumin.authoring.service;

import com.leyon.lumin.authoring.repository.ContentVersionMapper;
import com.leyon.lumin.authoring.repository.ContentVersionRow;
import com.leyon.lumin.authoring.repository.DraftMapper;
import com.leyon.lumin.authoring.repository.DraftRow;
import com.leyon.lumin.catalog.service.WorkService;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.identity.service.ProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DraftLifecycleServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 12, 0);
    private static final String WORK = "00000000-0000-4000-8000-000000000001";
    private static final String CHAPTER = "00000000-0000-4000-8000-000000000002";
    private static final String SHA = "a".repeat(64);

    @Mock
    private DraftMapper draftMapper;
    @Mock
    private ContentVersionMapper contentVersionMapper;
    @Mock
    private WorkService workService;
    @Mock
    private ProfileService profileService;

    private DraftLifecycleService service;

    @BeforeEach
    void setUp() {
        service = new DraftLifecycleService(draftMapper, contentVersionMapper, workService,
                profileService, new ObjectMapper());
    }

    private DraftRow draft(String state, long lock, String tags, String body) {
        return new DraftRow("d1", WORK, null, "WORK", null, "标题", "简介", null, tags,
                null, body, state, NOW, NOW, "s1", "s1", lock, null);
    }

    private ContentVersionRow version(String state, String sourceDraftId) {
        return new ContentVersionRow("v1", WORK, null, "WORK", 1L, sourceDraftId,
                "标题", "简介", null, "[]", null, "object/v1", "sha", "{}", "material",
                10, state, null, 1L, NOW, NOW, "s1", "s1", 1L);
    }

    @Test
    void loadDraftReturns404ForNullDeletedOrMissingRow() {
        assertThrows(LuminBusinessException.class, () -> service.loadDraft(null));
        when(draftMapper.findById("d1")).thenReturn(null);
        assertThrows(LuminBusinessException.class, () -> service.loadDraft("d1"));
        DraftRow deleted = new DraftRow("d1", WORK, null, "WORK", null, "标题", "简介",
                null, "[]", null, null, "EDITABLE", NOW, NOW, "s1", "s1", 1L, NOW);
        when(draftMapper.findById("d1")).thenReturn(deleted);
        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> service.loadDraft("d1"));
        assertEquals(ErrorCode.B2004, error.code());
    }

    @Test
    void loadDraftMapsTagsAndBody() {
        when(draftMapper.findById("d1")).thenReturn(draft("EDITABLE", 2L, "[\"a\",\"b\"]", "正文"));

        DraftLifecycleService.DraftSnapshot snapshot = service.loadDraft("d1");

        assertEquals("d1", snapshot.id());
        assertEquals("WORK", snapshot.targetType());
        assertEquals(List.of("a", "b"), snapshot.tags());
        assertEquals("正文", snapshot.bodyText());
        assertEquals(2L, snapshot.lockVersion());
    }

    @Test
    void findPublishedVersionReturnsNullUnlessApproved() {
        assertNull(service.findPublishedVersion(null));
        when(contentVersionMapper.findById("v1")).thenReturn(version("SUBMITTED", "d1"));
        assertNull(service.findPublishedVersion("v1"));
        when(contentVersionMapper.findById("v1")).thenReturn(version("APPROVED", "d1"));

        DraftLifecycleService.PublishedVersionRef ref = service.findPublishedVersion("v1");

        assertEquals("v1", ref.versionId());
        assertEquals("object/v1", ref.objectKey());
        assertEquals(1L, ref.policyVersion());
    }

    @Test
    void findVersionOwnerReturnsNullWhenMissing() {
        assertNull(service.findVersionOwner(null));
        when(contentVersionMapper.findById("v1")).thenReturn(null);
        assertNull(service.findVersionOwner("v1"));
    }

    @Test
    void findVersionMaterialHandlesMissingWorkAndProfile() {
        assertNull(service.findVersionMaterial(null));
        when(contentVersionMapper.findById("v1")).thenReturn(version("SUBMITTED", "d1"));
        when(workService.getWorkSummary(WORK)).thenReturn(null);

        assertEquals(null, service.findVersionMaterial("v1").authorAgeBand());

        when(workService.getWorkSummary(WORK)).thenReturn(
                new WorkService.WorkSummary(WORK, "a1", "VALID", "ACTIVE", 1L));
        when(profileService.requireProfile("a1"))
                .thenReturn(new ProfileService.ProfileSummary("p1", "ADULT", "ACTIVE"));

        assertEquals("ADULT", service.findVersionMaterial("v1").authorAgeBand());
    }

    @Test
    void findVersionMaterialsCoversEmptyShortCircuitAndMissingProfile() {
        assertEquals(List.of(), service.findVersionMaterials(null));
        assertEquals(List.of(), service.findVersionMaterials(List.of()));
        assertEquals(List.of(), service.findVersionMaterials(java.util.Arrays.asList(null, null)));
        when(contentVersionMapper.findByIds(any())).thenReturn(List.of());
        assertEquals(List.of(), service.findVersionMaterials(List.of("v1")));

        when(contentVersionMapper.findByIds(any())).thenReturn(List.of(version("SUBMITTED", "d1")));
        when(workService.getWorkSummaries(any())).thenReturn(java.util.Map.of());
        assertEquals(null, service.findVersionMaterials(List.of("v1")).get(0).authorAgeBand());

        when(workService.getWorkSummaries(any())).thenReturn(
                java.util.Map.of(WORK, new WorkService.WorkSummary(WORK, "a1", "VALID", "ACTIVE", 1L)));
        when(profileService.findProfiles(any())).thenReturn(java.util.Map.of());
        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> service.findVersionMaterials(List.of("v1")));
        assertEquals(ErrorCode.B2004, error.code());
    }

    @Test
    void findReviewMaterialFallsBackToTitleWhenDraftBodyMissing() {
        assertNull(service.findReviewMaterial(null));
        when(contentVersionMapper.findById("v1")).thenReturn(version("SUBMITTED", null));

        assertEquals("标题：标题\n简介：简介", service.findReviewMaterial("v1").text());

        when(contentVersionMapper.findById("v1")).thenReturn(version("SUBMITTED", "d1"));
        when(draftMapper.findById("d1")).thenReturn(draft("SUBMITTED", 1L, "[]", "正文"));
        assertEquals("正文", service.findReviewMaterial("v1").text());
    }

    @Test
    void persistSubmissionRejectsNonEditableAndStaleLock() {
        DraftLifecycleService.DraftSnapshot snapshot = new DraftLifecycleService.DraftSnapshot(
                "d1", WORK, CHAPTER, "CHAPTER", "标题", null, null, List.of(), "正文", "EDITABLE", 3L);
        when(draftMapper.findByIdForUpdate("d1")).thenReturn(draft("SUBMITTED", 3L, "[]", "正文"));
        assertThrows(LuminBusinessException.class, () -> service.persistSubmission(snapshot, "v1",
                "object/v1", SHA, 10, 1L, 3L, "s1", NOW));

        when(draftMapper.findByIdForUpdate("d1")).thenReturn(draft("EDITABLE", 4L, "[]", "正文"));
        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> service.persistSubmission(snapshot, "v1", "object/v1", SHA, 10, 1L, 3L, "s1", NOW));
        assertEquals(ErrorCode.B2002, error.code());
    }

    @Test
    void persistSubmissionValidatesSnapshotAndWritesVersion() {
        DraftLifecycleService.DraftSnapshot snapshot = new DraftLifecycleService.DraftSnapshot(
                "d1", WORK, CHAPTER, "CHAPTER", "标题", null, null, List.of(), "正文", "EDITABLE", 3L);
        when(draftMapper.findByIdForUpdate("d1")).thenReturn(draft("EDITABLE", 3L, "[]", "正文"));
        when(contentVersionMapper.nextRevisionNo(anyString(), anyString(), any())).thenReturn(1L);
        when(draftMapper.submit(anyString(), anyLong(), anyString(), any())).thenReturn(1);

        DraftLifecycleService.VersionPersisted result = service.persistSubmission(snapshot, "v1",
                "object/v1", SHA, 10, 1L, 3L, "s1", NOW);

        assertEquals("v1", result.versionId());
        verify(contentVersionMapper).insert(any(ContentVersionRow.class));
        verify(draftMapper).submit("d1", 3L, "s1", NOW);
    }

    @Test
    void persistSubmissionRollsBackWhenOptimisticSubmitLosesRace() {
        DraftLifecycleService.DraftSnapshot snapshot = new DraftLifecycleService.DraftSnapshot(
                "d1", WORK, CHAPTER, "CHAPTER", "标题", null, null, List.of(), "正文", "EDITABLE", 3L);
        when(draftMapper.findByIdForUpdate("d1")).thenReturn(draft("EDITABLE", 3L, "[]", "正文"));
        when(contentVersionMapper.nextRevisionNo(anyString(), anyString(), any())).thenReturn(1L);
        when(draftMapper.submit(anyString(), anyLong(), anyString(), any())).thenReturn(0);

        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> service.persistSubmission(snapshot, "v1", "object/v1", SHA, 10, 1L, 3L, "s1", NOW));
        assertEquals(ErrorCode.B2002, error.code());
        verify(draftMapper, never()).findById("d1");
    }
}
