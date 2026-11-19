package com.leyon.lumin.support;

import com.leyon.lumin.catalog.api.CategoryController;
import com.leyon.lumin.catalog.service.CategoryService;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.foundation.web.GlobalExceptionHandler;
import com.leyon.lumin.foundation.web.IdempotentExecutor;
import com.leyon.lumin.governance.api.RestrictionController;
import com.leyon.lumin.governance.service.RestrictionQueryService;
import com.leyon.lumin.governance.service.RestrictionService;
import com.leyon.lumin.moderation.api.AppealController;
import com.leyon.lumin.moderation.api.ReviewCaseController;
import com.leyon.lumin.moderation.api.dto.CaseDetailView;
import com.leyon.lumin.moderation.api.dto.DecisionItemView;
import com.leyon.lumin.moderation.api.dto.ReviewContentView;
import com.leyon.lumin.moderation.service.AppealQueryService;
import com.leyon.lumin.moderation.service.AppealService;
import com.leyon.lumin.moderation.service.ReviewCaseQueryService;
import com.leyon.lumin.moderation.service.ReviewCaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 增量四十 6 个读取端点的 HTTP 接入层用例（standalone MockMvc，不依赖 MySQL）：
 * 统一信封、嵌套响应、ETag、Cache-Control、分页字段与 B2004 错误映射。
 */
class ModerationReadApiTest {

    private static final String SUBJECT = "7f7f7f7f-0000-4000-8000-0000000000c3";
    private static final String ROLE = "REVIEWER_L2";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 11, 0);

    private final ReviewCaseService reviewCaseService = mock(ReviewCaseService.class);
    private final ReviewCaseQueryService reviewCaseQueryService = mock(ReviewCaseQueryService.class);
    private final RestrictionQueryService restrictionQueryService = mock(RestrictionQueryService.class);
    private final AppealQueryService appealQueryService = mock(AppealQueryService.class);
    private final CategoryService categoryService = mock(CategoryService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        IdempotentExecutor executor = mock(IdempotentExecutor.class);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new ReviewCaseController(reviewCaseService,
                                reviewCaseQueryService, executor),
                        new RestrictionController(mock(RestrictionService.class),
                                restrictionQueryService, executor),
                        new AppealController(mock(AppealService.class),
                                appealQueryService, executor),
                        new CategoryController(categoryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void caseQueueReturnsContractPageWithDecisions() throws Exception {
        when(reviewCaseService.listCases(SUBJECT, ROLE, null, 10))
                .thenReturn(new ReviewCaseService.Page(
                        List.of(new CaseDetailView("case-1", "work-1", "version-1", "QUEUED",
                                "LOW", "P2", "C2", NOW.plusHours(20), null, 2L,
                                List.of(new DecisionItemView("decision-1", "reviewer-1",
                                        "HIDE", "L1", "COMMUNITY_VIOLATION", "违规",
                                        List.of("a:b"), NOW)))),
                        "next-case"));

        mockMvc.perform(get("/api/v1/review-cases").param("limit", "10").with(identity()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items[0].id").value("case-1"))
                .andExpect(jsonPath("$.data.items[0].authorAgeBand").value("C2"))
                .andExpect(jsonPath("$.data.items[0].decisions[0].decision").value("HIDE"))
                .andExpect(jsonPath("$.data.nextCursor").value("next-case"));
    }

    @Test
    void caseDetailReturnsEtagAndFullDecisionHistory() throws Exception {
        when(reviewCaseQueryService.getCase(SUBJECT, ROLE, "case-1"))
                .thenReturn(new CaseDetailView("case-1", "work-1", "version-1", "WAITING_SECOND",
                        "LOW", "P2", "C2", NOW.plusHours(20), NOW.plusMinutes(10), 3L,
                        List.of(new DecisionItemView("decision-1", "reviewer-1", "HIDE",
                                "L1", "COMMUNITY_VIOLATION", "违规", List.of("a:b"), NOW))));

        mockMvc.perform(get("/api/v1/review-cases/case-1").with(identity()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").value("case-1"))
                .andExpect(jsonPath("$.data.authorAgeBand").value("C2"))
                .andExpect(jsonPath("$.data.decisions[0].action").doesNotExist())
                .andExpect(jsonPath("$.data.decisions[0].decision").value("HIDE"))
                .andExpect(jsonPath("$.data.decisions[0].evidenceRefs[0]").value("a:b"));
    }

    @Test
    void reviewContentIsNotCacheable() throws Exception {
        when(reviewCaseQueryService.getReviewContent(SUBJECT, ROLE, "case-1"))
                .thenReturn(new ReviewContentView("case-1", "标题", "正文",
                        "lumin:" + SUBJECT + ":case-1", NOW));

        mockMvc.perform(get("/api/v1/review-cases/case-1/content").with(identity()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.caseId").value("case-1"))
                .andExpect(jsonPath("$.data.title").value("标题"))
                .andExpect(jsonPath("$.data.watermark").value("lumin:" + SUBJECT + ":case-1"));
    }

    @Test
    void restrictionListAndDetailExposePageAndEtag() throws Exception {
        var request = new RestrictionQueryService.RestrictionRequestView("work-1", null,
                "case-1", "TEMPORARY", NOW.plusDays(2).toString(), "违规");
        var view = new RestrictionQueryService.RestrictionView("restriction-1", SUBJECT,
                NOW.toString(), request, 2L, "ACTIVE", List.of());
        when(restrictionQueryService.listVisible(eq(SUBJECT), eq(ROLE), eq(null), eq(10),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(new RestrictionQueryService.Page(List.of(view), "next-1"));
        when(restrictionQueryService.getVisible(eq(SUBJECT), eq(ROLE), eq("restriction-1"),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class))).thenReturn(view);

        mockMvc.perform(get("/api/v1/restrictions").param("limit", "10").with(identity()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("restriction-1"))
                .andExpect(jsonPath("$.data.items[0].request.kind").value("TEMPORARY"))
                .andExpect(jsonPath("$.data.nextCursor").value("next-1"));

        mockMvc.perform(get("/api/v1/restrictions/restriction-1").with(identity()))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.data.state").value("ACTIVE"));
    }

    @Test
    void appealListAndDetailExposePageAndEtag() throws Exception {
        var view = new AppealQueryService.AppealView("appeal-1", "case-1", "OPEN", 1L,
                NOW.plusHours(72).toString(), null, "author-1", NOW.toString(),
                new AppealQueryService.AppealRequestView("case-1", "有异议", List.of("a:b")),
                List.of());
        when(appealQueryService.listVisible(eq(SUBJECT), eq("AUTHOR"), eq(null), eq(10),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(new AppealQueryService.Page(List.of(view), null));
        when(appealQueryService.getVisible(eq(SUBJECT), eq("AUTHOR"), eq("appeal-1")))
                .thenReturn(view);

        mockMvc.perform(get("/api/v1/appeals").param("limit", "10")
                        .with(identity("AUTHOR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].request.caseId").value("case-1"));

        mockMvc.perform(get("/api/v1/appeals/appeal-1").with(identity("AUTHOR")))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.data.id").value("appeal-1"));
    }

    @Test
    void categoryListReturnsContractPage() throws Exception {
        when(categoryService.list(eq(SUBJECT), eq(null), eq(2),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class)))
                .thenReturn(new CategoryService.Page(List.of(
                        new CategoryService.CategoryView("category-1", "文学", null)), "next-1"));

        mockMvc.perform(get("/api/v1/categories").param("limit", "2").with(identity()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value("category-1"))
                .andExpect(jsonPath("$.data.items[0].name").value("文学"))
                .andExpect(jsonPath("$.data.nextCursor").value("next-1"));
    }

    @Test
    void businessNotFoundMapsToErrorEnvelope() throws Exception {
        when(reviewCaseQueryService.getCase(SUBJECT, ROLE, "missing"))
                .thenThrow(new LuminBusinessException(ErrorCode.B2004));

        mockMvc.perform(get("/api/v1/review-cases/missing").with(identity()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("B2004"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    private static RequestPostProcessor identity() {
        return identity(ROLE);
    }

    private static RequestPostProcessor identity(String role) {
        return request -> {
            request.setAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT, SUBJECT);
            request.setAttribute(DemoAuthenticationFilter.ATTRIBUTE_ROLE, role);
            return request;
        };
    }
}
