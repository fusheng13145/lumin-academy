package com.leyon.lumin.support;

import com.leyon.lumin.authoring.api.MeDraftsController;
import com.leyon.lumin.authoring.api.dto.DraftContractView;
import com.leyon.lumin.authoring.service.DraftCommandService;
import com.leyon.lumin.catalog.api.MeWorkspaceController;
import com.leyon.lumin.catalog.service.WorkService;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.foundation.web.GlobalExceptionHandler;
import com.leyon.lumin.publication.api.MePublicationWorkflowController;
import com.leyon.lumin.publication.service.PublicationWorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 作者读取面 HTTP 接入层用例（standalone MockMvc，不依赖 MySQL）：
 * getMyWork 的 ETag、listMyDrafts/listMyPublicationWorkflows 的契约分页信封。
 */
class AuthorReadApiTest {

    private static final String SUBJECT = "7f7f7f7f-0000-4000-8000-0000000000e1";

    private final WorkService workService = mock(WorkService.class);
    private final DraftCommandService draftCommandService = mock(DraftCommandService.class);
    private final PublicationWorkflowService workflowService =
            mock(PublicationWorkflowService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new MeWorkspaceController(workService),
                        new MeDraftsController(draftCommandService),
                        new MePublicationWorkflowController(workflowService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getMyWorkReturnsEtagAndAuthorView() throws Exception {
        when(workService.getMyWork(SUBJECT, "work-1")).thenReturn(
                new WorkService.MyWorkDetail("work-1", "ACTIVE", "作品标题", "draft-1",
                        "EDITABLE", "version-1", 4L));

        mockMvc.perform(get("/api/v1/me/works/work-1")
                        .requestAttr(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT, SUBJECT))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"4\""))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").value("work-1"))
                .andExpect(jsonPath("$.data.state").value("ACTIVE"))
                .andExpect(jsonPath("$.data.title").value("作品标题"))
                .andExpect(jsonPath("$.data.draftId").value("draft-1"))
                .andExpect(jsonPath("$.data.draftState").value("EDITABLE"))
                .andExpect(jsonPath("$.data.publishedVersionId").value("version-1"))
                .andExpect(jsonPath("$.data.revision").value(4));
    }

    @Test
    void getMyWorkMapsNotFoundToB2004() throws Exception {
        when(workService.getMyWork(SUBJECT, "missing"))
                .thenThrow(new LuminBusinessException(ErrorCode.B2004));

        mockMvc.perform(get("/api/v1/me/works/missing")
                        .requestAttr(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT, SUBJECT))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("B2004"));
    }

    @Test
    void listMyWorksReturnsContractPageWithGovernanceState() throws Exception {
        when(workService.listMyWorks(eq(SUBJECT), isNull(), eq(10)))
                .thenReturn(new WorkService.Page<>(
                        List.of(new WorkService.AuthorWorkView("work-1", "REMOVED", "作品标题",
                                "draft-1", "EDITABLE", "version-1", 4L)),
                        "next-work"));

        mockMvc.perform(get("/api/v1/me/works")
                        .param("limit", "10")
                        .requestAttr(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT, SUBJECT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items[0].id").value("work-1"))
                .andExpect(jsonPath("$.data.items[0].state").value("REMOVED"))
                .andExpect(jsonPath("$.data.items[0].title").value("作品标题"))
                .andExpect(jsonPath("$.data.items[0].draftId").value("draft-1"))
                .andExpect(jsonPath("$.data.items[0].publishedVersionId").value("version-1"))
                .andExpect(jsonPath("$.data.items[0].revision").value(4))
                .andExpect(jsonPath("$.data.nextCursor").value("next-work"));
    }

    @Test
    void listMyChaptersReturnsContractPageWithGovernanceState() throws Exception {
        when(workService.listMyChapters(eq(SUBJECT), eq("work-1"), isNull(), eq(10)))
                .thenReturn(new WorkService.Page<>(
                        List.of(new WorkService.AuthorChapterView("chapter-1", "work-1", 1,
                                "TERMINATED", "第一章", "draft-1", "EDITABLE", "version-1", 2L)),
                        "next-chapter"));

        mockMvc.perform(get("/api/v1/me/works/work-1/chapters")
                        .param("limit", "10")
                        .requestAttr(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT, SUBJECT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items[0].id").value("chapter-1"))
                .andExpect(jsonPath("$.data.items[0].workId").value("work-1"))
                .andExpect(jsonPath("$.data.items[0].sequenceNo").value(1))
                .andExpect(jsonPath("$.data.items[0].state").value("TERMINATED"))
                .andExpect(jsonPath("$.data.items[0].title").value("第一章"))
                .andExpect(jsonPath("$.data.items[0].revision").value(2))
                .andExpect(jsonPath("$.data.nextCursor").value("next-chapter"));
    }

    @Test
    void listMyDraftsReturnsContractPage() throws Exception {        DraftContractView view = DraftContractView.of("draft-1", 2, "EDITABLE", "CHAPTER",
                "work-1", "chapter-1", null, "第一章", null, null, List.of(),
                null, "正文");
        when(draftCommandService.listMyDrafts(eq(SUBJECT), isNull(), eq(10),
                any(LocalDateTime.class)))
                .thenReturn(new DraftCommandService.Page(List.of(view), "next-draft"));

        mockMvc.perform(get("/api/v1/me/drafts")
                        .param("limit", "10")
                        .requestAttr(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT, SUBJECT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items[0].id").value("draft-1"))
                .andExpect(jsonPath("$.data.items[0].state").value("EDITABLE"))
                .andExpect(jsonPath("$.data.items[0].content.targetType").value("CHAPTER"))
                .andExpect(jsonPath("$.data.items[0].content.title").value("第一章"))
                .andExpect(jsonPath("$.data.items[0].content.body").value("正文"))
                .andExpect(jsonPath("$.data.nextCursor").value("next-draft"));
    }

    @Test
    void listMyPublicationWorkflowsReturnsContractPage() throws Exception {
        when(workflowService.listMyWorkflows(eq(SUBJECT), eq("cursor-1"), eq(10),
                any(LocalDateTime.class)))
                .thenReturn(new PublicationWorkflowService.Page(
                        List.of(new PublicationWorkflowService.WorkflowView("workflow-1",
                                "version-1", "WAITING_SECOND", 3L, null, null)),
                        "next-workflow"));

        mockMvc.perform(get("/api/v1/me/publication-workflows")
                        .param("cursor", "cursor-1")
                        .param("limit", "10")
                        .requestAttr(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT, SUBJECT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.items[0].id").value("workflow-1"))
                .andExpect(jsonPath("$.data.items[0].versionId").value("version-1"))
                .andExpect(jsonPath("$.data.items[0].state").value("WAITING_SECOND"))
                .andExpect(jsonPath("$.data.items[0].revision").value(3))
                .andExpect(jsonPath("$.data.nextCursor").value("next-workflow"));
    }
}
