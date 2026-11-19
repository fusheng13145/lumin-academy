package com.leyon.lumin.moderation.service;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.port.ContentMaterialPort;
import com.leyon.lumin.moderation.repository.ReviewCaseMapper;
import com.leyon.lumin.moderation.repository.ReviewCaseRow;
import com.leyon.lumin.moderation.repository.ReviewDecisionMapper;
import com.leyon.lumin.moderation.repository.ReviewDecisionRow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** UGC-020 案件详情与受控材料读取：角色、领取人、租约、材料边界。 */
class ReviewCaseQueryServiceTest {

    private static final String SUBJECT = "7f7f7f7f-0000-4000-8000-0000000000c3";
    private static final String OTHER = "8f8f8f8f-0000-4000-8000-0000000000b2";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 0, 0);

    private final ReviewCaseMapper caseMapper = mock(ReviewCaseMapper.class);
    private final ReviewDecisionMapper decisionMapper = mock(ReviewDecisionMapper.class);
    private final ContentMaterialPort materialPort = mock(ContentMaterialPort.class);
    private final ReviewCaseQueryService service = new ReviewCaseQueryService(
            caseMapper, decisionMapper, materialPort, new ObjectMapper());

    @Test
    void caseDetailReturnsAuthorAgeBandAndFullDecisionHistory() {
        when(caseMapper.findById("case-1")).thenReturn(row("QUEUED", null, null));
        when(materialPort.findVersionMaterial("version-1"))
                .thenReturn(new ContentMaterialPort.MaterialRef("version-1", "work-1",
                        null, "WORK", "a".repeat(64), 1L, "C2"));
        when(decisionMapper.findByCase("case-1")).thenReturn(List.of(
                new ReviewDecisionRow("decision-1", "case-1", "actor-1", "HUMAN",
                        "HIDE", null, "COMMUNITY_VIOLATION", "违规", "[\"a:b\"]",
                        1, 1L, NOW, NOW, "actor-1", "actor-1", 1L)));

        var view = service.getCase(SUBJECT, "REVIEWER_L2", "case-1");

        assertEquals("C2", view.authorAgeBand());
        assertEquals(1, view.decisions().size());
        assertEquals(List.of("a:b"), view.decisions().get(0).evidenceRefs());
        assertEquals(2L, view.revision());
    }

    @Test
    void caseDetailHidesCaseClaimedByAnotherReviewer() {
        when(caseMapper.findById("case-1")).thenReturn(row("CLAIMED", OTHER, NOW.plusMinutes(5)));

        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> service.getCase(SUBJECT, "REVIEWER_L2", "case-1"));

        assertEquals(ErrorCode.B2004, error.code());
    }

    @Test
    void caseDetailRejectsNonReviewerRole() {
        when(caseMapper.findById("case-1")).thenReturn(row("QUEUED", null, null));

        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> service.getCase(SUBJECT, "AUTHOR", "case-1"));

        assertEquals(ErrorCode.B2004, error.code());
    }

    @Test
    void reviewContentRequiresCurrentClaimAndActiveLease() {
        when(caseMapper.findById("case-1"))
                .thenReturn(row("CLAIMED", SUBJECT, LocalDateTime.now().plusMinutes(10)));
        when(materialPort.findReviewMaterial("version-1"))
                .thenReturn(new ContentMaterialPort.ReviewMaterial("version-1", "标题", "正文"));

        var view = service.getReviewContent(SUBJECT, "REVIEWER_L2", "case-1");

        assertEquals("标题", view.title());
        assertEquals("正文", view.text());
        assertEquals("lumin:" + SUBJECT + ":case-1", view.watermark());
    }

    @Test
    void reviewContentRejectsExpiredLease() {
        when(caseMapper.findById("case-1"))
                .thenReturn(row("CLAIMED", SUBJECT, LocalDateTime.now().minusMinutes(1)));

        LuminBusinessException error = assertThrows(LuminBusinessException.class,
                () -> service.getReviewContent(SUBJECT, "REVIEWER_L2", "case-1"));

        assertEquals(ErrorCode.B2004, error.code());
    }

    private ReviewCaseRow row(String state, String assignee, LocalDateTime lease) {
        return new ReviewCaseRow("case-1", "version-1", "workflow-1", "work-1", null,
                "SUBMISSION", "LOW", "P2", state, "queue-1", 1, assignee, lease,
                NOW.plusHours(2), NOW.plusHours(24), 1L, NOW, NOW, "system", "system",
                2L, null);
    }
}
