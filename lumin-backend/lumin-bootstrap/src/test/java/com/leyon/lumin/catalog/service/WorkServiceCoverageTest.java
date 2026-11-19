package com.leyon.lumin.catalog.service;

import com.leyon.lumin.catalog.repository.ChapterMapper;
import com.leyon.lumin.catalog.repository.ChapterStateRow;
import com.leyon.lumin.catalog.repository.WorkMapper;
import com.leyon.lumin.catalog.repository.WorkRow;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkServiceCoverageTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 12, 0);
    private static final String SUBJECT = "s1";
    private static final String WORK = "w1";

    @Mock
    private WorkMapper workMapper;
    @Mock
    private ChapterMapper chapterMapper;

    private WorkService service;

    @BeforeEach
    void setUp() {
        service = new WorkService(workMapper, chapterMapper, "work-test-cursor-key-32-bytes");
    }

    private WorkRow work(String state, String prior, long lock) {
        return new WorkRow(WORK, SUBJECT, state, prior, "VALID", 1, null, NOW, NOW,
                SUBJECT, SUBJECT, lock);
    }

    @Test
    void createWorkPersistsDraftAndReturnsId() {
        String id = service.createWork(SUBJECT, NOW);
        assertNotNull(id);
        verify(workMapper).insert(any(WorkRow.class));
    }

    @Test
    void requireOwnedWorkRejectsMissingAndForeign() {
        assertCode(ErrorCode.B2004, () -> service.requireOwnedWork(null, SUBJECT));
        when(workMapper.findById(WORK)).thenReturn(work("ACTIVE", null, 2L));
        assertCode(ErrorCode.B2004, () -> service.requireOwnedWork(WORK, "other"));
        assertEquals("ACTIVE", service.requireOwnedWork(WORK, SUBJECT).businessState());
    }

    @Test
    void changeBusinessStateChecksOwnershipLockAndWriteResult() {
        assertCode(ErrorCode.B2004, () -> service.changeBusinessState(SUBJECT, null,
                "ACTIVE", false, 1L, NOW));
        when(workMapper.findById(WORK)).thenReturn(work("DRAFT", null, 2L));
        assertCode(ErrorCode.B2002, () -> service.changeBusinessState(SUBJECT, WORK,
                "ACTIVE", false, 1L, NOW));
        when(workMapper.updateBusinessStateIfLock(anyString(), anyString(), any(), any(),
                anyString(), any(), anyString(), anyLong())).thenReturn(0);
        assertCode(ErrorCode.B2002, () -> service.changeBusinessState(SUBJECT, WORK,
                "ACTIVE", false, 2L, NOW));
        when(workMapper.updateBusinessStateIfLock(anyString(), anyString(), any(), any(),
                anyString(), any(), anyString(), anyLong())).thenReturn(1);
        assertEquals(3L, service.changeBusinessState(SUBJECT, WORK, "ACTIVE", false, 2L, NOW));
    }

    @Test
    void summariesHandleMissingAndBatch() {
        when(workMapper.findById(WORK)).thenReturn(null);
        assertEquals(null, service.getWorkSummary(WORK));
        when(workMapper.findById(WORK)).thenReturn(work("ACTIVE", null, 1L));
        assertEquals(SUBJECT, service.getWorkSummary(WORK).authorId());

        assertEquals(java.util.Map.of(), service.getWorkSummaries(null));
        assertEquals(java.util.Map.of(), service.getWorkSummaries(List.of()));
        when(workMapper.findByIds(any())).thenReturn(List.of(work("ACTIVE", null, 1L)));
        assertEquals(1, service.getWorkSummaries(List.of(WORK)).size());
    }

    @Test
    void createChapterRejectsFrozenStateStaleLockAndWriteRace() {
        when(workMapper.findById(WORK)).thenReturn(work("COMPLETED", null, 2L));
        assertCode(ErrorCode.B2001, () -> service.createChapter(SUBJECT, WORK, "\"2\"", NOW));
        when(workMapper.findById(WORK)).thenReturn(work("ACTIVE", null, 2L));
        assertCode(ErrorCode.B2002, () -> service.createChapter(SUBJECT, WORK, "\"1\"", NOW));
        when(workMapper.allocateChapterNo(anyString(), anyLong(), anyString(), any())).thenReturn(0);
        assertCode(ErrorCode.B2002, () -> service.createChapter(SUBJECT, WORK, "\"2\"", NOW));
    }

    @Test
    void createChapterAllocatesSequenceAndInserts() {
        when(workMapper.findById(WORK)).thenReturn(work("DRAFT", null, 2L));
        when(workMapper.allocateChapterNo(WORK, 2L, SUBJECT, NOW)).thenReturn(1);
        when(workMapper.selectNextChapterNo(WORK)).thenReturn(null);

        String chapterId = service.createChapter(SUBJECT, WORK, "\"2\"", NOW);

        assertNotNull(chapterId);
        verify(chapterMapper).insert(eq(chapterId), eq(WORK), eq(0), eq(SUBJECT), eq(NOW));
    }

    @Test
    void requireChapterInWorkRejectsMissingMismatch() {
        assertCode(ErrorCode.P1001, () -> service.requireChapterInWork(null, WORK));
        when(chapterMapper.findWorkIdById("c1")).thenReturn("w2");
        assertCode(ErrorCode.P1001, () -> service.requireChapterInWork("c1", WORK));
        when(chapterMapper.findWorkIdById("c1")).thenReturn(WORK);
        service.requireChapterInWork("c1", WORK);
    }

    @Test
    void markWorkLifecycleTransitionsAndMissingRows() {
        assertCode(ErrorCode.B2004, () -> service.markFirstVersionPublished(null, "sys", NOW));
        when(workMapper.findById(WORK)).thenReturn(work("DRAFT", null, 1L));
        service.markFirstVersionPublished(WORK, "sys", NOW);

        when(workMapper.findById(WORK)).thenReturn(work("ACTIVE", null, 1L));
        service.markTakedown(WORK, "sys", NOW);
        verify(workMapper).updateBusinessState(eq(WORK), eq("REMOVED"), eq("ACTIVE"),
                eq(null), eq("sys"), eq(NOW), eq("ACTIVE"));

        when(workMapper.findById(WORK)).thenReturn(work("REMOVED", "ACTIVE", 1L));
        service.markRestored(WORK, "sys", NOW);
        verify(workMapper).updateBusinessState(eq(WORK), eq("ACTIVE"), eq("REMOVED"),
                eq(null), eq("sys"), eq(NOW), eq("REMOVED"));
    }

    @Test
    void markChapterLifecycleTransitionsAndMissingRows() {
        assertCode(ErrorCode.B2004, () -> service.markChapterTakedown(null, "sys", NOW));
        when(chapterMapper.findStateById("c1")).thenReturn(new ChapterStateRow("c1", "ACTIVE", null));
        service.markChapterTakedown("c1", "sys", NOW);
        verify(chapterMapper).updateBusinessState(eq("c1"), eq("REMOVED"), eq("ACTIVE"),
                eq("sys"), eq(NOW), eq("ACTIVE"));

        when(chapterMapper.findStateById("c1")).thenReturn(new ChapterStateRow("c1", "REMOVED", null));
        assertCode(ErrorCode.B2004, () -> service.markChapterRestored("c1", "sys", NOW));
        when(chapterMapper.findStateById("c1")).thenReturn(new ChapterStateRow("c1", "REMOVED", "ACTIVE"));
        service.markChapterRestored("c1", "sys", NOW);
        verify(chapterMapper).updateBusinessState(eq("c1"), eq("ACTIVE"), eq("REMOVED"),
                eq("sys"), eq(NOW), eq("REMOVED"));
    }

    private static void assertCode(ErrorCode expected,
                                   org.junit.jupiter.api.function.Executable action) {
        LuminBusinessException error = assertThrows(LuminBusinessException.class, action);
        assertEquals(expected, error.code());
    }
}
