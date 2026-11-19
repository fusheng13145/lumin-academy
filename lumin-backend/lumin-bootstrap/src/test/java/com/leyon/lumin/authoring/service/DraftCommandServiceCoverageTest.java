package com.leyon.lumin.authoring.service;

import com.leyon.lumin.authoring.api.dto.DraftCreateRequest;
import com.leyon.lumin.authoring.api.dto.DraftSaveRequest;
import com.leyon.lumin.authoring.repository.DraftMapper;
import com.leyon.lumin.authoring.repository.DraftRow;
import com.leyon.lumin.catalog.service.WorkService;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DraftCommandServiceCoverageTest {

    private static final String SUBJECT = "author-1";
    private static final String WORK = "00000000-0000-4000-8000-000000000001";
    private static final String CHAPTER = "00000000-0000-4000-8000-000000000002";
    private static final String DRAFT = "00000000-0000-4000-8000-000000000003";

    @Mock
    private DraftMapper draftMapper;
    @Mock
    private WorkService workService;
    @Mock
    private AuditRecorder auditRecorder;

    private DraftCommandService service;

    @BeforeEach
    void setUp() {
        service = new DraftCommandService(draftMapper, workService, new ObjectMapper(),
                auditRecorder, "draft-test-cursor-key-32-bytes");
    }

    private DraftRow draft(String targetType, String state, long lock) {
        return new DraftRow(DRAFT, WORK, "CHAPTER".equals(targetType) ? CHAPTER : null,
                targetType, null, "标题", "简介", null, "[]", null, "正文", state,
                LocalDateTime.of(2026, 11, 19, 12, 0), LocalDateTime.of(2026, 11, 19, 12, 0),
                SUBJECT, SUBJECT, lock, null);
    }

    private DraftCreateRequest workRequest(String title) {
        return new DraftCreateRequest(WORK, null, title, "简介",
                "00000000-0000-4000-8000-000000000004", List.of(), null);
    }

    @Test
    void createDraftRejectsInvalidPayloadAndUnavailableWork() {
        assertCode(ErrorCode.P1001, () -> service.createDraft(SUBJECT, workRequest(" ")));
        doThrow(new LuminBusinessException(ErrorCode.B2004))
                .when(workService).requireOwnedWork(WORK, SUBJECT);
        assertCode(ErrorCode.B2004, () -> service.createDraft(SUBJECT, workRequest("标题")));

        doReturn(new WorkService.WorkSummary(WORK, SUBJECT, "PENDING", "DRAFT", 1L))
                .when(workService).requireOwnedWork(WORK, SUBJECT);
        assertCode(ErrorCode.B2001, () -> service.createDraft(SUBJECT, workRequest("标题")));
    }

    @Test
    void createDraftRejectsChapterMismatchAndPersists() {
        when(workService.requireOwnedWork(WORK, SUBJECT)).thenReturn(
                new WorkService.WorkSummary(WORK, SUBJECT, "VALID", "DRAFT", 1L));
        doThrow(new LuminBusinessException(ErrorCode.P1001))
                .when(workService).requireChapterInWork(CHAPTER, WORK);
        DraftCreateRequest chapterRequest = new DraftCreateRequest(WORK, CHAPTER, "标题",
                null, null, List.of(), "正文");
        assertCode(ErrorCode.P1001, () -> service.createDraft(SUBJECT, chapterRequest));

        DraftCreateRequest workRequest = workRequest("标题");
        assertNotNull(service.createDraft(SUBJECT, workRequest).id());
        verify(draftMapper).insert(any(DraftRow.class));
    }

    @Test
    void listMyDraftsValidatesLimitCursorAndPages() {
        assertCode(ErrorCode.P1001, () -> service.listMyDrafts(SUBJECT, null, 0,
                LocalDateTime.now()));
        assertCode(ErrorCode.P1001, () -> service.listMyDrafts(SUBJECT, "bad", 10,
                LocalDateTime.now()));
        when(draftMapper.listByAuthor(eq(SUBJECT), any(), any(), eq(2)))
                .thenReturn(List.of(draft("WORK", "EDITABLE", 1L)));
        assertEquals(1, service.listMyDrafts(SUBJECT, null, 1, LocalDateTime.now()).items().size());
    }

    @Test
    void saveDraftRejectsFrozenStaleInvalidAndWriteRace() {
        when(draftMapper.findById(DRAFT)).thenReturn(draft("WORK", "SUBMITTED", 2L));
        assertCode(ErrorCode.B2001, () -> service.saveDraft(SUBJECT, DRAFT, "\"2\"",
                new DraftSaveRequest("标题", "简介", null, List.of(), null)));
        when(draftMapper.findById(DRAFT)).thenReturn(draft("WORK", "EDITABLE", 2L));
        assertCode(ErrorCode.B2002, () -> service.saveDraft(SUBJECT, DRAFT, "\"1\"",
                new DraftSaveRequest("标题", "简介", null, List.of(), null)));
        assertCode(ErrorCode.P1001, () -> service.saveDraft(SUBJECT, DRAFT, "bad",
                new DraftSaveRequest("标题", "简介", null, List.of(), null)));
        assertCode(ErrorCode.P1001, () -> service.saveDraft(SUBJECT, DRAFT, "\"2\"",
                new DraftSaveRequest(" ", "简介", null, List.of(), null)));
        when(draftMapper.updateEditableContent(anyString(), anyString(), any(), any(),
                anyString(), any(), anyString(), any(), anyLong())).thenReturn(0);
        assertCode(ErrorCode.B2002, () -> service.saveDraft(SUBJECT, DRAFT, "\"2\"",
                new DraftSaveRequest("标题", "简介",
                        "00000000-0000-4000-8000-000000000004", List.of(), null)));
    }

    @Test
    void saveDraftPersistsWorkAndChapterPayloads() {
        when(draftMapper.findById(DRAFT)).thenReturn(draft("WORK", "EDITABLE", 2L));
        when(draftMapper.updateEditableContent(anyString(), anyString(), any(), any(),
                anyString(), any(), anyString(), any(), anyLong())).thenReturn(1);
        assertEquals(3L, service.saveDraft(SUBJECT, DRAFT, "\"2\"",
                new DraftSaveRequest("标题", "简介",
                        "00000000-0000-4000-8000-000000000004", List.of(), null)).revision());

        when(draftMapper.findById(DRAFT)).thenReturn(draft("CHAPTER", "EDITABLE", 2L));
        assertEquals(3L, service.saveDraft(SUBJECT, DRAFT, "\"2\"",
                new DraftSaveRequest("标题", null, null, List.of(), "正文")).revision());
    }

    private static void assertCode(ErrorCode expected,
                                   org.junit.jupiter.api.function.Executable action) {
        LuminBusinessException error = assertThrows(LuminBusinessException.class, action);
        assertEquals(expected, error.code());
    }
}
