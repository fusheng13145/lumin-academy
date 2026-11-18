package com.leyon.lumin.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T29 阅读进度契约（07 T29；05 §3.4 saveProgress/getProgress；04 §5.1）：
 * 位置 0/10000 合法持久化、-1/10001 与负时长 400；客户端时间超前 >5 分钟拒绝
 * （+4 分钟放行）；乐观锁 If-Match（首次 "0"，错版本 412）；B 访问 A 的进度 404；
 * 未发布/不存在章节 404（可读性闸门经端口由 publication 裁决）；
 * 章节再发布（V1→V2）后保存重绑至 V2 且 versionChanged=true——
 * V1 位置从不被呈现为 V2 位置（04 §5.1 版本变化提示而不跨版本乱套）。
 */
@SpringBootTest(properties = "lumin.review.calibration-valid=true")
@AutoConfigureMockMvc
class T29ReadingProgressIntegrationTest extends MySqlIntegrationBase {

    /** 嵌套配置：抽检闸恒未命中——AUTO 链路确定性验证（同 Governance/Reader 链类）。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class NeverSampledGate {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.leyon.lumin.moderation.service.AutoSampleGate neverSampled() {
            return caseId -> false;
        }
    }

    private static final String READER_C3 = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C3;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.leyon.lumin.foundation.event.OutboxDispatcher dispatcher;

    @BeforeEach
    void seedFixtures() {
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

    private String putProgress(String subject, String chapterId, String ifMatch,
                               int position, int readSeconds, Instant clientUpdatedAt)
            throws Exception {
        return mockMvc.perform(put("/api/v1/me/reading-progress/" + chapterId)
                        .header("X-Lumin-Demo-Subject", subject)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", ifMatch)
                        .contentType("application/json")
                        .content("{\"positionBasisPoints\":" + position
                                + ",\"readSeconds\":" + readSeconds
                                + ",\"clientUpdatedAt\":\"" + clientUpdatedAt + "\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 发布作品与章节；返回 {workId, chapterId, versionId}。 */
    private String[] publishWorkAndChapter(String title) throws Exception {
        String workId = field(mockMvc.perform(post("/api/v1/works")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String workDraft = field(mockMvc.perform(post("/api/v1/drafts")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"title\":\"" + title + "\","
                                + "\"synopsis\":\"简介\",\"categoryId\":\""
                                + MySqlFixtures.CATEGORY_LITERATURE + "\",\"tags\":[]}"))
                .andExpect(status().isOk()), "id");
        submitAndApprove(workDraft);
        long lock = jdbc.queryForObject(
                "SELECT lock_version FROM work WHERE id = ?", Long.class, workId);
        String chapterId = field(mockMvc.perform(post("/api/v1/works/" + workId + "/chapters")
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"" + lock + "\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String chapterVersion = publishChapterDraft(workId, chapterId, "正文第一版");
        return new String[]{workId, chapterId, chapterVersion};
    }

    /** 新建章节草稿 → 提交 → 机审 AUTO 通过；返回新版本 ID（可重复调用发布修订）。 */
    private String publishChapterDraft(String workId, String chapterId, String body)
            throws Exception {
        String draftId = field(mockMvc.perform(post("/api/v1/drafts")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"chapterId\":\"" + chapterId
                                + "\",\"title\":\"第一章\",\"body\":\"" + body + "\"}"))
                .andExpect(status().isOk()), "id");
        return submitAndApprove(draftId);
    }

    /** 提交草稿 → 机审 LOW PASS（AUTO 链）→ 返回该草稿对应的已发布版本 ID。 */
    private String submitAndApprove(String draftId) throws Exception {
        mockMvc.perform(post("/api/v1/drafts/" + draftId + "/submissions")
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
                "SELECT version_id FROM publication_workflow WHERE id = ?", String.class, workflowId);
        String materialSha = jdbc.queryForObject(
                "SELECT material_sha256 FROM content_version WHERE id = ?", String.class, versionId);
        mockMvc.perform(post("/internal/v1/machine-reports")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"caseId\":\"" + caseId + "\",\"workflowId\":\"" + workflowId
                                + "\",\"versionId\":\"" + versionId + "\",\"policyVersion\":1,"
                                + "\"materialSha256\":\"" + materialSha
                                + "\",\"modelVersion\":\"guard-v1\",\"risk\":\"LOW\",\"riskFlags\":[],"
                                + "\"suggestedDecision\":\"PASS\",\"grade\":\"G\",\"confidence\":0.99,"
                                + "\"evidenceRefs\":[\"ev:1\"],\"inputTokens\":100,\"outputTokens\":10,"
                                + "\"durationMs\":1000}"))
                .andExpect(status().isOk());
        dispatcher.deliverPending();
        return versionId;
    }

    @Test
    void progressBoundariesOwnershipAndClockSkewContract() throws Exception {
        String[] ctx = publishWorkAndChapter("进度作品");
        String chapterId = ctx[1];
        String versionId = ctx[2];
        Instant now = Instant.now();
        // 首次写 If-Match "0"：位置下边界 0 合法
        String first = putProgress(READER_C3, chapterId, "\"0\"", 0, 30, now.minusSeconds(60));
        assertThat(first).contains("\"etag\":\"1\"").contains("\"versionChanged\":false");
        mockMvc.perform(get("/api/v1/me/reading-progress/" + chapterId)
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.positionBasisPoints").value(0))
                .andExpect(jsonPath("$.data.versionId").value(versionId))
                .andExpect(jsonPath("$.data.etag").value("1"));
        // 上边界 10000 合法；时钟超前 +4 分钟放行（≤5 分钟口径）
        String second = putProgress(READER_C3, chapterId, "\"1\"", 10000, 120,
                now.plusSeconds(240));
        assertThat(second).contains("\"etag\":\"2\"");
        // 非法边界：-1 / 10001 / 负时长 → 400/P1001
        assertThat(putProgress(READER_C3, chapterId, "\"2\"", -1, 10, now))
                .contains("P1001");
        assertThat(putProgress(READER_C3, chapterId, "\"2\"", 10001, 10, now))
                .contains("P1001");
        assertThat(putProgress(READER_C3, chapterId, "\"2\"", 100, -1, now))
                .contains("P1001");
        // 客户端时间超前 +6 分钟 → 拒绝（>5 分钟口径）
        assertThat(putProgress(READER_C3, chapterId, "\"2\"", 100, 10,
                now.plusSeconds(360))).contains("P1001");
        // 乐观锁：已存在行再用 "0" → 412/B2002；过期 ETag → 412/B2002
        assertThat(putProgress(READER_C3, chapterId, "\"0\"", 100, 10, now))
                .contains("B2002");
        assertThat(putProgress(READER_C3, chapterId, "\"99\"", 100, 10, now))
                .contains("B2002");
        // 越权：B（另一读者替身）访问 A 的进度 → 404；B 首写自己的行合法
        mockMvc.perform(get("/api/v1/me/reading-progress/" + chapterId)
                        .header("X-Lumin-Demo-Subject",
                                com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C2))
                .andExpect(status().isNotFound());
        assertThat(putProgress(
                com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C2,
                chapterId, "\"0\"", 500, 10, now)).contains("\"etag\":\"1\"");
        // 可读性闸门：不存在/未发布章节 → 404（publication 裁决）
        assertThat(putProgress(READER_C3, UUID.randomUUID().toString(), "\"0\"", 100, 10, now))
                .contains("B2004");
    }

    @Test
    void republishRebindsProgressToNewVersionWithChangedFlag() throws Exception {
        String[] ctx = publishWorkAndChapter("修订作品");
        String workId = ctx[0];
        String chapterId = ctx[1];
        String v1 = ctx[2];
        Instant now = Instant.now();
        putProgress(READER_C3, chapterId, "\"0\"", 5000, 60, now);
        // V1 期间进度绑定 V1
        mockMvc.perform(get("/api/v1/me/reading-progress/" + chapterId)
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionId").value(v1));
        // 再发布修订 → 指针换至 V2（03：允许已存在章节修订）
        String v2 = publishChapterDraft(workId, chapterId, "正文第二版");
        assertThat(v2).isNotEqualTo(v1);
        assertThat(jdbc.queryForObject(
                "SELECT version_id FROM publication_pointer WHERE target_type = 'CHAPTER' "
                        + "AND target_id = ?", String.class, chapterId)).isEqualTo(v2);
        // 读者正文已切 V2；保存进度（合法 ETag）→ 重绑 V2 + versionChanged=true
        String saved = putProgress(READER_C3, chapterId, "\"1\"", 3000, 90, now);
        assertThat(saved).contains("\"versionChanged\":true").contains("\"etag\":\"2\"");
        mockMvc.perform(get("/api/v1/me/reading-progress/" + chapterId)
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionId").value(v2))
                .andExpect(jsonPath("$.data.positionBasisPoints").value(3000));
        // 未保存前 GET 仍呈现 V1 绑定（版本变化提示由 UI 比对 versionId 触发，
        // V1 位置从不被呈现为 V2 位置——不跨版本乱套）
    }
}
