package com.leyon.lumin.moderation.service;

import com.leyon.lumin.foundation.port.PublishedVersionPort;
import com.leyon.lumin.foundation.port.ReviewCasePort;
import com.leyon.lumin.moderation.repository.ReviewCaseMapper;
import com.leyon.lumin.moderation.repository.ReviewCaseRow;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 举报建案：冻结建案时的当前公开版本，供后续受控阅卷读取（增量五十一）。 */
class ReviewCasePortAdapterTest {

    private static final String WORK = "11111111-1111-4111-8111-111111111111";
    private static final String CHAPTER = "22222222-2222-4222-8222-222222222222";
    private static final String VERSION = "33333333-3333-4333-8333-333333333333";

    private final ReviewCaseMapper caseMapper = mock(ReviewCaseMapper.class);
    private final PublishedVersionPort publishedVersionPort = mock(PublishedVersionPort.class);
    private final ReviewCasePortAdapter adapter =
            new ReviewCasePortAdapter(caseMapper, publishedVersionPort);

    @Test
    void reportCaseFreezesCurrentPublishedChapterVersion() {
        when(publishedVersionPort.findPublishedVersionId("CHAPTER", CHAPTER)).thenReturn(VERSION);

        adapter.createReportCase(new ReviewCasePort.ReportCaseRequest(WORK, CHAPTER, 1L));

        ArgumentCaptor<ReviewCaseRow> captor = ArgumentCaptor.forClass(ReviewCaseRow.class);
        verify(caseMapper).insert(captor.capture());
        ReviewCaseRow row = captor.getValue();
        assertEquals(VERSION, row.versionId());
        assertEquals("REPORT", row.source());
        assertEquals("QUEUED", row.state());
    }

    @Test
    void reportCaseKeepsVersionNullWhenTargetNotPublished() {
        when(publishedVersionPort.findPublishedVersionId("WORK", WORK)).thenReturn(null);

        adapter.createReportCase(new ReviewCasePort.ReportCaseRequest(WORK, null, 1L));

        ArgumentCaptor<ReviewCaseRow> captor = ArgumentCaptor.forClass(ReviewCaseRow.class);
        verify(caseMapper).insert(captor.capture());
        assertNull(captor.getValue().versionId());
    }
}
