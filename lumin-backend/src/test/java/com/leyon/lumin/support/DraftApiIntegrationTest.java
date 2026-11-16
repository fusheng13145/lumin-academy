package com.leyon.lumin.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 垂直切片端点集成测试（真实 MySQL + MockMvc；T07/T08/T09 子集）：
 * 统一信封、幂等回放与 B2003、If-Match 428/412、B2001 冻结、越权/不存在 404、
 * 载荷校验 400。骨架认证替身主体（DemoAuthenticationFilter.STUB_SUBJECT）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class DraftApiIntegrationTest extends MySqlIntegrationBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedFixtures() {
        MySqlFixtures.insertStubAuthorProfile(jdbc);
        MySqlFixtures.insertAuthorWorkAndCategory(jdbc);
    }

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    private String createWork() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/works")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.resourceType").value("work"))
                .andExpect(jsonPath("$.data.state").value("DRAFT"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andReturn();
        return extractId(result);
    }

    private String createWorkDraft(String workId, String key, String title) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/drafts")
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("""
                                {"workId":"%s","title":"%s","synopsis":"简介","categoryId":"%s","tags":["奇幻"]}
                                """.formatted(workId, title, MySqlFixtures.CATEGORY_LITERATURE))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.state").value("EDITABLE"))
                .andReturn();
        return extractId(result);
    }

    private static String extractId(MvcResult result) throws java.io.UnsupportedEncodingException {
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        int idIndex = body.indexOf("\"id\":\"");
        return body.substring(idIndex + 6, body.indexOf('"', idIndex + 6));
    }

    @Test
    void createWorkThenDraftThenGet() throws Exception {
        String workId = createWork();
        String draftId = createWorkDraft(workId, uuidKey(), "启明");
        mockMvc.perform(get("/api/v1/drafts/" + draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.title").value("启明"))
                .andExpect(jsonPath("$.data.state").value("EDITABLE"))
                .andExpect(jsonPath("$.data.revision").value(1))
                .andExpect(jsonPath("$.data.tags[0]").value("奇幻"))
                .andExpect(header().string("ETag", "\"1\""));
    }

    @Test
    void saveRequiresIfMatchAndMatchingRevision() throws Exception {
        String workId = createWork();
        String draftId = createWorkDraft(workId, uuidKey(), "初稿");
        // 缺 If-Match → 428/P1002（05 §2）
        mockMvc.perform(put("/api/v1/drafts/" + draftId)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"title\":\"修订\",\"synopsis\":\"简介\",\"categoryId\":\""
                                + MySqlFixtures.CATEGORY_LITERATURE + "\",\"tags\":[]}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("P1002"));
        // ETag 不匹配 → 412/B2002
        mockMvc.perform(put("/api/v1/drafts/" + draftId)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"9\"")
                        .contentType("application/json")
                        .content("{\"title\":\"修订\",\"synopsis\":\"简介\",\"categoryId\":\""
                                + MySqlFixtures.CATEGORY_LITERATURE + "\",\"tags\":[]}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("B2002"));
        // 正确 ETag → 200，revision 递增，回读生效
        mockMvc.perform(put("/api/v1/drafts/" + draftId)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"title\":\"修订稿\",\"synopsis\":\"新简介\",\"categoryId\":\""
                                + MySqlFixtures.CATEGORY_LITERATURE + "\",\"tags\":[\"科幻\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(2));
        mockMvc.perform(get("/api/v1/drafts/" + draftId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("修订稿"))
                .andExpect(header().string("ETag", "\"2\""));
    }

    @Test
    void idempotentReplayAndConflict() throws Exception {
        String workId = createWork();
        String key = uuidKey();
        String first = mockMvc.perform(post("/api/v1/drafts")
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("""
                                {"workId":"%s","title":"原稿","synopsis":"简介","categoryId":"%s","tags":[]}
                                """.formatted(workId, MySqlFixtures.CATEGORY_LITERATURE))
                )
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        // 同键同载荷：原样回放（T09：响应引用一致）
        mockMvc.perform(post("/api/v1/drafts")
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("""
                                {"workId":"%s","title":"原稿","synopsis":"简介","categoryId":"%s","tags":[]}
                                """.formatted(workId, MySqlFixtures.CATEGORY_LITERATURE))
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(
                        first.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1")));
        // 同键不同载荷 → 409/B2003
        mockMvc.perform(post("/api/v1/drafts")
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content("""
                                {"workId":"%s","title":"另一份","synopsis":"简介","categoryId":"%s","tags":[]}
                                """.formatted(workId, MySqlFixtures.CATEGORY_LITERATURE))
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("B2003"));
    }

    @Test
    void missingIdempotencyKeyAndInvalidPayload() throws Exception {
        String workId = createWork();
        // 写操作缺 Idempotency-Key → 400/P1001（05 §2）
        mockMvc.perform(post("/api/v1/drafts")
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"title\":\"标题\",\"synopsis\":\"简介\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("P1001"));
        // WORK 载荷带 body → 400/P1001（05 §4.1）
        mockMvc.perform(post("/api/v1/drafts")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"title\":\"标题\",\"synopsis\":\"简介\",\"body\":\"多余正文\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("P1001"));
    }

    @Test
    void unknownDraftAndFrozenDraftBehavePerSpec() throws Exception {
        String workId = createWork();
        String draftId = createWorkDraft(workId, uuidKey(), "待冻结");
        // 不存在 → 404/B2004（越权与不存在统一 404，05 §2）
        mockMvc.perform(get("/api/v1/drafts/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("B2004"));
        // SUBMITTED 原草稿冻结：保存 → 409/B2001（03 §2.2）
        jdbc.update("UPDATE draft SET state = 'SUBMITTED' WHERE id = ?", draftId);
        mockMvc.perform(put("/api/v1/drafts/" + draftId)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"title\":\"不应生效\",\"synopsis\":\"简介\",\"categoryId\":\""
                                + MySqlFixtures.CATEGORY_LITERATURE + "\",\"tags\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("B2001"));
    }

    @Test
    void chapterDraftMustBelongToOwnedWork() throws Exception {
        String workId = createWork();
        // 夹具章节 C 属于作者 A 的作品 W，不属于骨架主体 → 归属校验 400/P1001
        mockMvc.perform(post("/api/v1/drafts")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"chapterId\":\""
                                + MySqlFixtures.CHAPTER_C + "\",\"title\":\"第一章\",\"body\":\"第一段\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("P1001"));
    }
}
