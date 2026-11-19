package com.leyon.lumin.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UGC-038 元数据搜索集成测试（docs/13 M1 一阶段）：标题/作者 LOCATE 匹配、
 * 分类/标签过滤、授权过滤与 /works 同源（PG_16 带闸、下架不出）、签名游标
 * 分页跨页无漏重、过滤摘要跨查询不可重放、空结果 200 空数组与不可用分开、
 * 空条件/超长 q/非法 UUID 拒绝。
 */
@SpringBootTest(properties = "lumin.review.calibration-valid=true")
@AutoConfigureMockMvc
class UGC038SearchIntegrationTest extends MySqlIntegrationBase {

    /** 嵌套配置：抽检闸恒未命中——AUTO 链路确定性验证（抽检函数另有单测）。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class NeverSampledGate {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.leyon.lumin.moderation.service.AutoSampleGate neverSampled() {
            return caseId -> false;
        }
    }

    private static final String AUTHOR = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.AUTHOR;
    private static final String READER_C3 = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C3;
    private static final String READER_C1 = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C1;
    private static final String SUBJECT_C3 =
            com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C3_SUBJECT;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.leyon.lumin.foundation.event.OutboxDispatcher dispatcher;

    @BeforeEach
    void seedFixtures() {
        MySqlFixtures.insertStubAuthorProfile(jdbc);
        MySqlFixtures.insertAuthorWorkAndCategory(jdbc);
        MySqlFixtures.insertReaderProfiles(jdbc);
    }

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(org.springframework.test.web.servlet.ResultActions actions,
                                String name) throws java.io.UnsupportedEncodingException {
        return com.leyon.lumin.support.TestResponseSupport.field(actions, name);
    }

    /** 发布作品（标题/标签/等级可选——机审 PASS + 投递完成）。 */
    private String publishWork(String title, List<String> tags, String grade) throws Exception {
        String tagsJson = tags == null || tags.isEmpty() ? "[]" : "[\""
                + String.join("\",\"", tags) + "\"]";
        String workId = field(mockMvc.perform(post_work(title, tagsJson))
                .andExpect(status().isOk()), "id");
        String draftId = field(mockMvc.perform(post_draft(workId, title, tagsJson, grade))
                .andExpect(status().isOk()), "id");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/drafts/" + draftId + "/submissions")
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isAccepted());
        dispatcher.deliverPending();
        String workflowId = jdbc.queryForObject(
                "SELECT id FROM publication_workflow WHERE version_id = (SELECT id FROM "
                        + "content_version WHERE source_draft_id = ?)", String.class, draftId);
        String caseId = jdbc.queryForObject(
                "SELECT case_id FROM publication_workflow WHERE id = ?", String.class, workflowId);
        String versionId = jdbc.queryForObject(
                "SELECT version_id FROM publication_workflow WHERE id = ?", String.class,
                workflowId);
        String materialSha = jdbc.queryForObject(
                "SELECT material_sha256 FROM content_version WHERE id = ?", String.class,
                versionId);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/internal/v1/machine-reports")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"caseId\":\"" + caseId + "\",\"workflowId\":\"" + workflowId
                                + "\",\"versionId\":\"" + versionId + "\",\"policyVersion\":1,"
                                + "\"materialSha256\":\"" + materialSha
                                + "\",\"modelVersion\":\"guard-v1\",\"risk\":\"LOW\",\"riskFlags\":[],"
                                + "\"suggestedDecision\":\"PASS\",\"grade\":\"" + grade
                                + "\",\"confidence\":0.99,\"evidenceRefs\":[\"ev:1\"],"
                                + "\"inputTokens\":100,\"outputTokens\":10,\"durationMs\":1000}"))
                .andExpect(status().isOk());
        dispatcher.deliverPending();
        return workId;
    }

    private org.springframework.test.web.servlet.RequestBuilder post_work(String title,
                                                                          String tagsJson) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/works")
                .header("X-Lumin-Demo-Subject", AUTHOR)
                .header("Idempotency-Key", uuidKey())
                .contentType("application/json").content("{}");
    }

    private org.springframework.test.web.servlet.RequestBuilder post_draft(String workId,
                                                                           String title,
                                                                           String tagsJson,
                                                                           String grade) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/drafts")
                .header("X-Lumin-Demo-Subject", AUTHOR)
                .header("Idempotency-Key", uuidKey())
                .contentType("application/json")
                .content("{\"workId\":\"" + workId + "\",\"title\":\"" + title + "\","
                        + "\"synopsis\":\"简介\",\"categoryId\":\""
                        + MySqlFixtures.CATEGORY_LITERATURE + "\",\"tags\":" + tagsJson + "}");
    }

    private List<String> searchTitles(String subject, String q, String tag) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/works/search")
                        .header("X-Lumin-Demo-Subject", subject)
                        .param("q", q)
                        .param("tag", tag))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        List<String> titles = new ArrayList<>();
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(body);
        node.get("data").forEach(item -> titles.add(item.get("title").asText()));
        return titles;
    }

    @Test
    void titleSearchMatchesSubstring() throws Exception {
        publishWork("魔法学徒传", List.of(), "G");
        publishWork("海商纪事", List.of(), "G");

        assertThat(searchTitles(READER_C3, "魔法", null)).containsExactly("魔法学徒传");
        assertThat(searchTitles(READER_C3, "纪事", null)).containsExactly("海商纪事");
    }

    @Test
    void authorSearchMatchesNicknameSubstring() throws Exception {
        publishWork("书名甲", List.of(), "G");
        publishWork("书名乙", List.of(), "G");
        // API 作者替身昵称「骨架作者」：按作者昵称子串命中两本
        assertThat(searchTitles(READER_C3, "骨架作者", null)).containsExactlyInAnyOrder(
                "书名甲", "书名乙");
    }

    @Test
    void tagFilterNarrowsResults() throws Exception {
        publishWork("奇幻之作", List.of("奇幻"), "G");
        publishWork("都市之作", List.of("都市"), "G");

        assertThat(searchTitles(READER_C3, null, "奇幻")).containsExactly("奇幻之作");
        assertThat(searchTitles(READER_C3, null, "都市")).containsExactly("都市之作");
        // 标签无命中 → 200 空数组（与不可用分开）
        mockMvc.perform(get("/api/v1/works/search")
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .param("tag", "不存在的标签"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void categoryFilterReturnsCategoryWorks() throws Exception {
        publishWork("文学一", List.of(), "G");
        publishWork("文学二", List.of(), "G");

        MvcResult result = mockMvc.perform(get("/api/v1/works/search")
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .param("categoryId", MySqlFixtures.CATEGORY_LITERATURE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).contains("文学一", "文学二");
    }

    @Test
    void ageBandGateAppliesToSearch() throws Exception {
        publishWork("深夜档案", List.of(), "PG_16");
        publishWork("日间故事", List.of(), "G");

        // C3 可见 PG_16；C1 仅 G/PG_12——与 /works 同源授权过滤
        assertThat(searchTitles(READER_C3, "档案", null)).containsExactly("深夜档案");
        assertThat(searchTitles(READER_C1, "档案", null)).isEmpty();
        assertThat(searchTitles(READER_C1, "日间", null)).containsExactly("日间故事");
    }

    @Test
    void removedWorkIsNotSearchable() throws Exception {
        String workId = publishWork("下架之书", List.of(), "G");
        jdbc.update("UPDATE work SET business_state = 'REMOVED' WHERE id = ?", workId);

        assertThat(searchTitles(READER_C3, "下架之书", null)).isEmpty();
    }

    @Test
    void searchPaginationAcrossPagesWithoutDuplication() throws Exception {
        List<String> expected = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            String title = "系列第" + i + "卷";
            publishWork(title, List.of(), "G");
            expected.add(title);
        }
        List<String> seen = new ArrayList<>();
        String cursor = null;
        for (int pageIdx = 0; pageIdx < 4; pageIdx++) {
            var builder = get("/api/v1/works/search")
                    .header("X-Lumin-Demo-Subject", READER_C3)
                    .param("q", "系列")
                    .param("limit", "2");
            if (cursor != null) {
                builder.param("cursor", cursor);
            }
            MvcResult result = mockMvc.perform(builder)
                    .andExpect(status().isOk())
                    .andReturn();
            var node = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(result.getResponse().getContentAsString());
            node.get("data").forEach(item -> seen.add(item.get("title").asText()));
            cursor = result.getResponse().getHeader("X-Lumin-Next-Cursor");
            if (cursor == null) {
                break;
            }
        }
        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(new HashSet<>(seen)).hasSize(seen.size());
    }

    @Test
    void cursorIsQueryBoundAndTamperIsRejected() throws Exception {
        publishWork("魔法书甲", List.of(), "G");
        publishWork("魔法书乙", List.of(), "G");
        publishWork("海商书丙", List.of(), "G");

        // q=魔法 第一页游标
        MvcResult first = mockMvc.perform(get("/api/v1/works/search")
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .param("q", "魔法")
                        .param("limit", "1"))
                .andExpect(status().isOk())
                .andReturn();
        String cursor = first.getResponse().getHeader("X-Lumin-Next-Cursor");
        assertThat(cursor).isNotNull();

        // 跨查询重放（q 换成海商）→ 过滤摘要不匹配 400
        mockMvc.perform(get("/api/v1/works/search")
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .param("q", "海商")
                        .param("cursor", cursor))
                .andExpect(status().isBadRequest());
        // 篡改 → 签名校验 400
        mockMvc.perform(get("/api/v1/works/search")
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .param("q", "魔法")
                        .param("cursor", cursor + "x"))
                .andExpect(status().isBadRequest());
        // 跨主体（C1 用 C3 的游标）→ 400
        mockMvc.perform(get("/api/v1/works/search")
                        .header("X-Lumin-Demo-Subject", READER_C1)
                        .param("q", "魔法")
                        .param("cursor", cursor))
                .andExpect(status().isBadRequest());
    }

    @Test
    void emptyQueryAndOversizedInputAreRejected() throws Exception {
        // 空条件 → 400（搜索与浏览分开：浏览走 /works）
        mockMvc.perform(get("/api/v1/works/search")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isBadRequest());
        // q 超 100 字符 → 400
        mockMvc.perform(get("/api/v1/works/search")
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .param("q", "长".repeat(101)))
                .andExpect(status().isBadRequest());
        // categoryId 非法 → 400
        mockMvc.perform(get("/api/v1/works/search")
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .param("categoryId", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }
}
