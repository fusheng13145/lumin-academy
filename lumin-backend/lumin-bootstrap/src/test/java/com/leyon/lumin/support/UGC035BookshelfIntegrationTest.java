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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UGC-035 收藏书架集成测试（docs/13 M1；docs/04 §6.1 bookmark；6.6）：
 * 收藏幂等（重复收藏无副作用）；取消收藏幂等；跨账号隔离（B 不可见/不可删
 * A 的书架行）；不可读状态（下架作品 readable=false）；收藏与历史分开；
 * 没有作品时真实空态。
 */
@SpringBootTest(properties = "lumin.review.calibration-valid=true")
@AutoConfigureMockMvc
class UGC035BookshelfIntegrationTest extends MySqlIntegrationBase {

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
    private static final String READER_C2 = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C2;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.leyon.lumin.foundation.event.OutboxDispatcher dispatcher;

    @org.junit.jupiter.api.BeforeEach
    void seedFixtures() {
        MySqlFixtures.insertStubAuthorProfile(jdbc);
        MySqlFixtures.insertAuthorWorkAndCategory(jdbc);
        MySqlFixtures.insertReaderProfiles(jdbc);
        seedReviewer(MySqlFixtures.REVIEWER_L1A, "sub-reviewer-l1a");
        seedReviewer(MySqlFixtures.REVIEWER_L2A, "sub-reviewer-l2a");
    }

    /** 限制复核会写 proposal_review，actor 必须有 user_profile 外键目标。 */
    private void seedReviewer(String userId, String oidcSubject) {
        jdbc.update("""
                INSERT IGNORE INTO user_profile (id, oidc_issuer, oidc_subject, nickname,
                    declared_age_band, verified_age_band, effective_age_band, account_status,
                    policy_revision, created_at, updated_at, created_by, updated_by)
                VALUES (?, 'https://id.example', ?, '审核员', 'C3', 'C3', 'C3', 'ACTIVE',
                    1, ?, ?, ?, ?)
                """, userId, oidcSubject, MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM);
    }

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(org.springframework.test.web.servlet.ResultActions actions,
                                String name) throws java.io.UnsupportedEncodingException {
        return com.leyon.lumin.support.TestResponseSupport.field(actions, name);
    }

    /** 发布一部 G 级作品。 */
    private String publishWork(String title) throws Exception {
        String workId = field(mockMvc.perform(post("/api/v1/works")
                        .header("X-Lumin-Demo-Subject", AUTHOR)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String draftId = field(mockMvc.perform(post("/api/v1/drafts")
                        .header("X-Lumin-Demo-Subject", AUTHOR)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"title\":\"" + title + "\","
                                + "\"synopsis\":\"简介\",\"categoryId\":\""
                                + MySqlFixtures.CATEGORY_LITERATURE + "\",\"tags\":[]}"))
                .andExpect(status().isOk()), "id");
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
                "SELECT version_id FROM publication_workflow WHERE id = ?", String.class,
                workflowId);
        String materialSha = jdbc.queryForObject(
                "SELECT material_sha256 FROM content_version WHERE id = ?", String.class,
                versionId);
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
        return workId;
    }

    @Test
    void bookmarkAddRemoveAndIsolation() throws Exception {
        String workId = publishWork("书架作品");
        // 收藏（幂等：重复收藏两次仍 1 行）
        mockMvc.perform(post("/api/v1/me/bookmarks")
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bookmarked").value(true));
        mockMvc.perform(post("/api/v1/me/bookmarks")
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\"}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM bookmark WHERE user_id = ? AND work_id = ?",
                Integer.class,
                com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C3_SUBJECT,
                workId)).isEqualTo(1);
        // 书架列表：readable=true（已发布作品）
        mockMvc.perform(get("/api/v1/me/bookmarks")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].workId").value(workId))
                .andExpect(jsonPath("$.data[0].readable").value(true))
                .andExpect(jsonPath("$.data[0].title").value("书架作品"));
        // 跨账号隔离：C2 的书架为空（真实空态），且无法删除 C3 的收藏
        mockMvc.perform(get("/api/v1/me/bookmarks")
                        .header("X-Lumin-Demo-Subject", READER_C2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(delete("/api/v1/me/bookmarks/" + workId)
                        .header("X-Lumin-Demo-Subject", READER_C2)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM bookmark WHERE user_id = ?",
                Integer.class,
                com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C3_SUBJECT))
                .isEqualTo(1);
        // 取消收藏 → 列表空
        mockMvc.perform(delete("/api/v1/me/bookmarks/" + workId)
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me/bookmarks")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void bookshelfShowsUnreadableStateForHiddenWork() throws Exception {
        String workId = publishWork("待下架书架作品");
        mockMvc.perform(post("/api/v1/me/bookmarks")
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\"}"))
                .andExpect(status().isOk());
        // 治理下架（TEMPORARY 生效）→ 作品不可读 → 书架条目 readable=false（不隐藏条目）
        String restrictionId = com.leyon.lumin.support.TestResponseSupport.field(
                mockMvc.perform(post("/api/v1/restrictions")
                        .header("X-Lumin-Demo-Subject",
                                com.leyon.lumin.foundation.web.DemoAuthenticationFilter.REVIEWER_L2A)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"caseId\":\"33333333-3333-4333-8333-333333333333\",\"kind\":\"TEMPORARY\","
                                + "\"reason\":\"下架测试\"}"))
                .andExpect(status().isOk()), "id");
        mockMvc.perform(post("/api/v1/restrictions/" + restrictionId + "/reviews")
                        .header("X-Lumin-Demo-Subject",
                                com.leyon.lumin.foundation.web.DemoAuthenticationFilter.REVIEWER_L1A)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"activate\":true}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me/bookmarks")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].readable").value(false));
    }

    @Test
    void readingHistoryIsSeparatedFromBookmarks() throws Exception {
        // 无进度无收藏 → 双空态
        mockMvc.perform(get("/api/v1/me/reading-history")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        // 发布作品+章节，保存阅读进度 → 历史出现；收藏列表仍空（收藏与历史分开）
        String workId = publishWork("历史作品");
        long lock = jdbc.queryForObject(
                "SELECT lock_version FROM work WHERE id = ?", Long.class, workId);
        String chapterId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/works/" + workId + "/chapters")
                                .header("Idempotency-Key", uuidKey())
                                .header("If-Match", "\"" + lock + "\"")
                                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String draftId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/drafts")
                                .header("Idempotency-Key", uuidKey())
                                .contentType("application/json")
                                .content("{\"workId\":\"" + workId + "\",\"chapterId\":\""
                                        + chapterId + "\",\"title\":\"第一章\",\"body\":\"正文\"}"))
                .andExpect(status().isOk()), "id");
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
                "SELECT case_id FROM publication_workflow WHERE id = ?", String.class,
                workflowId);
        String versionId = jdbc.queryForObject(
                "SELECT version_id FROM publication_workflow WHERE id = ?", String.class,
                workflowId);
        String materialSha = jdbc.queryForObject(
                "SELECT material_sha256 FROM content_version WHERE id = ?", String.class,
                versionId);
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
        // 保存阅读进度（PUT reading-progress）
        String positionBody = "{\"positionBasisPoints\":4200,\"readSeconds\":120,"
                + "\"clientUpdatedAt\":\"" + java.time.Instant.now().minusSeconds(60)
                + "\"}";
        mockMvc.perform(put("/api/v1/me/reading-progress/" + chapterId)
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"0\"")
                        .contentType("application/json").content(positionBody))
                .andExpect(status().isOk());
        // 历史 1 条；收藏仍 0（分开）
        mockMvc.perform(get("/api/v1/me/reading-history")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].chapterId").value(chapterId))
                .andExpect(jsonPath("$.data[0].workTitle").value("历史作品"))
                .andExpect(jsonPath("$.data[0].positionBasisPoints").value(4200));
        mockMvc.perform(get("/api/v1/me/bookmarks")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }
}
