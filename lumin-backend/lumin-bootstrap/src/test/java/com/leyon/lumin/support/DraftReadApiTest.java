package com.leyon.lumin.support;

import com.leyon.lumin.authoring.api.DraftController;
import com.leyon.lumin.authoring.api.dto.DraftContractView;
import com.leyon.lumin.authoring.service.DraftCommandService;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import com.leyon.lumin.foundation.web.GlobalExceptionHandler;
import com.leyon.lumin.foundation.web.IdempotentExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * getDraft HTTP 接入层用例：契约 DraftView 的嵌套 content 形态与 ETag。
 */
class DraftReadApiTest {

    private static final String SUBJECT = "7f7f7f7f-0000-4000-8000-0000000000d1";

    private final DraftCommandService draftCommandService = mock(DraftCommandService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new DraftController(draftCommandService, mock(IdempotentExecutor.class)))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getWorkDraftReturnsNestedContractContent() throws Exception {
        when(draftCommandService.getDraft(SUBJECT, "draft-1")).thenReturn(
                DraftContractView.of("draft-1", 3, "EDITABLE", "WORK", "work-1", null,
                        null, "标题", "简介", "category-1", List.of("奇幻"), null, null));

        mockMvc.perform(get("/api/v1/drafts/draft-1")
                        .requestAttr(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT, SUBJECT))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.id").value("draft-1"))
                .andExpect(jsonPath("$.data.revision").value(3))
                .andExpect(jsonPath("$.data.state").value("EDITABLE"))
                .andExpect(jsonPath("$.data.content.targetType").value("WORK"))
                .andExpect(jsonPath("$.data.content.workId").value("work-1"))
                .andExpect(jsonPath("$.data.content.title").value("标题"))
                .andExpect(jsonPath("$.data.content.synopsis").value("简介"))
                .andExpect(jsonPath("$.data.content.categoryId").value("category-1"))
                .andExpect(jsonPath("$.data.content.tags[0]").value("奇幻"))
                .andExpect(jsonPath("$.data.title").doesNotExist());
    }
}
