package com.leyon.lumin.catalog.service;

import com.leyon.lumin.catalog.repository.ChapterListRow;
import com.leyon.lumin.catalog.repository.ChapterMapper;
import com.leyon.lumin.catalog.repository.MyWorkDetailRow;
import com.leyon.lumin.catalog.repository.MyWorkRow;
import com.leyon.lumin.catalog.repository.WorkMapper;
import com.leyon.lumin.catalog.repository.WorkRow;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.cursor.CursorCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkServiceTest {

    private static final String SUBJECT = "7f7f7f7f-0000-4000-8000-0000000000e1";
    private static final String CURSOR_KEY = "test-cursor-key-0123456789abcdef";

    @Mock
    private WorkMapper workMapper;
    @Mock
    private ChapterMapper chapterMapper;
    private WorkService workService;

    @BeforeEach
    void setUp() {
        workService = new WorkService(workMapper, chapterMapper, CURSOR_KEY);
    }

    @Test
    void getMyWorkMapsNullableDraftAndPublishedPointers() {
        when(workMapper.findMyWorkDetail("w1", "s1")).thenReturn(new MyWorkDetailRow(
                "w1", "DRAFT", null, "d1", "EDITABLE", null, 2L));

        WorkService.MyWorkDetail detail = workService.getMyWork("s1", "w1");

        assertEquals("w1", detail.id());
        assertEquals("DRAFT", detail.state());
        assertEquals("d1", detail.draftId());
        assertEquals("EDITABLE", detail.draftState());
        assertEquals(2L, detail.revision());
    }

    @Test
    void getMyWorkNotFoundOrForeignIs404() {
        when(workMapper.findMyWorkDetail("w1", "s2")).thenReturn(null);

        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> workService.getMyWork("s2", "w1"));
        assertEquals(ErrorCode.B2004, error.code());
    }

    @Test
    void allChaptersPublishedOnlyWhenNoNonActiveChapter() {
        when(chapterMapper.countUnpublishedByWork("w1")).thenReturn(0);
        assertEquals(true, workService.allChaptersPublished("w1"));

        when(chapterMapper.countUnpublishedByWork("w1")).thenReturn(1);
        assertEquals(false, workService.allChaptersPublished("w1"));
    }

    @Test
    void listMyWorksMapsGovernanceStateAndIssuesSignedCursor() {
        LocalDateTime at = LocalDateTime.of(2026, 11, 19, 10, 0);
        String firstId = "00000000-0000-4000-8000-00000000000a";
        when(workMapper.listMyWorks(eq(SUBJECT), any(LocalDateTime.class), anyString(), eq(2)))
                .thenReturn(List.of(
                        new MyWorkRow(firstId, "REMOVED", "VALID", 4L, "draft-1",
                                "EDITABLE", "草稿标题", 1L, null, null, null, "version-1", at),
                        new MyWorkRow("00000000-0000-4000-8000-00000000000b", "ACTIVE",
                                "VALID", 3L, null, null, null, null, "已发布标题", "PUBLISHED",
                                "G", "version-2", at)));

        WorkService.Page<WorkService.AuthorWorkView> page =
                workService.listMyWorks(SUBJECT, null, 1);

        assertEquals(1, page.items().size());
        assertEquals("REMOVED", page.items().get(0).state());
        assertEquals("草稿标题", page.items().get(0).title());
        assertEquals("version-1", page.items().get(0).publishedVersionId());
        assertEquals(4L, page.items().get(0).revision());
        assertNotNull(page.nextCursor());
        CursorCodec.DecodedCursor decoded = CursorCodec.decode(page.nextCursor(),
                CURSOR_KEY.getBytes(StandardCharsets.UTF_8), Instant.now(),
                CursorCodec.filterDigest("list=my-works;viewer=" + SUBJECT));
        assertEquals(ErrorCode.OK, decoded.code());
        assertEquals(at.toString(), decoded.payload().sortKeyValue());
        assertEquals(UUID.fromString(firstId), decoded.payload().lastId());
        assertEquals(UUID.fromString(SUBJECT), decoded.payload().subjectId());
    }

    @Test
    void listMyWorksRejectsInvalidLimitAndCursor() {
        LuminBusinessException limitError = assertThrows(LuminBusinessException.class,
                () -> workService.listMyWorks(SUBJECT, null, 0));
        assertEquals(ErrorCode.P1001, limitError.code());

        LuminBusinessException cursorError = assertThrows(LuminBusinessException.class,
                () -> workService.listMyWorks(SUBJECT, "not-a-cursor", 10));
        assertEquals(ErrorCode.P1001, cursorError.code());
    }

    @Test
    void listMyChaptersMapsContractFieldsAndCursor() {
        LocalDateTime at = LocalDateTime.of(2026, 11, 19, 10, 0);
        when(workMapper.findById("work-1")).thenReturn(new WorkRow("work-1", SUBJECT,
                "ACTIVE", null, "VALID", 2, null, at, at, SUBJECT, SUBJECT, 3L));
        String firstId = "00000000-0000-4000-8000-00000000000c";
        when(chapterMapper.listByWorkWithDraft(eq("work-1"), eq(0), anyString(), eq(2)))
                .thenReturn(List.of(
                        new ChapterListRow(firstId, 1, "TERMINATED", "draft-1", "EDITABLE",
                                "草稿章名", 1L, "work-1", "第一章", "version-1", 2L),
                        new ChapterListRow("00000000-0000-4000-8000-00000000000d", 2, "ACTIVE",
                                null, null, null, null, "work-1", "第二章", "version-2", 3L)));

        WorkService.Page<WorkService.AuthorChapterView> page =
                workService.listMyChapters(SUBJECT, "work-1", null, 1);

        assertEquals(1, page.items().size());
        assertEquals("work-1", page.items().get(0).workId());
        assertEquals(1, page.items().get(0).sequenceNo());
        assertEquals("TERMINATED", page.items().get(0).state());
        assertEquals("第一章", page.items().get(0).title());
        assertEquals(2L, page.items().get(0).revision());
        assertNotNull(page.nextCursor());
        CursorCodec.DecodedCursor decoded = CursorCodec.decode(page.nextCursor(),
                CURSOR_KEY.getBytes(StandardCharsets.UTF_8), Instant.now(),
                CursorCodec.filterDigest("list=my-chapters;work=work-1;viewer=" + SUBJECT));
        assertEquals(ErrorCode.OK, decoded.code());
        assertEquals("1", decoded.payload().sortKeyValue());
        assertEquals(UUID.fromString(firstId), decoded.payload().lastId());
    }

    @Test
    void listMyChaptersRejectsForeignWork() {
        when(workMapper.findById("work-1")).thenReturn(new WorkRow("work-1",
                "00000000-0000-4000-8000-0000000000ff", "ACTIVE", null, "VALID", 1,
                null, null, null, "x", "x", 1L));

        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> workService.listMyChapters(SUBJECT, "work-1", null, 10));
        assertEquals(ErrorCode.B2004, error.code());
    }
}
