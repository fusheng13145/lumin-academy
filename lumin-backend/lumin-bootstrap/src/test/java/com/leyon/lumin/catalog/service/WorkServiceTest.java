package com.leyon.lumin.catalog.service;

import com.leyon.lumin.catalog.repository.ChapterMapper;
import com.leyon.lumin.catalog.repository.MyWorkDetailRow;
import com.leyon.lumin.catalog.repository.WorkMapper;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkServiceTest {

    @Mock
    private WorkMapper workMapper;
    @Mock
    private ChapterMapper chapterMapper;
    @InjectMocks
    private WorkService workService;

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
}
