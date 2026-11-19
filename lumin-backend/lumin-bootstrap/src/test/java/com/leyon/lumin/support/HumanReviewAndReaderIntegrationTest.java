package com.leyon.lumin.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 人审闭环与读者阅读链集成测试（03 §2.4/§3.1 / 05 §3.3/§3.4；T20/T21/T27 子集）：
 * 审核员替身领取（REVIEWER_L1A）、非审核员 403、裁决发布（人审最终裁决链）、
 * HIGH 双审（首审不终局）、读者详情/正文/未发布 404。
 */
@SpringBootTest(properties = "lumin.review.calibration-valid=true")
@AutoConfigureMockMvc
class HumanReviewAndReaderIntegrationTest extends MySqlIntegrationBase {

    /** 嵌套配置：抽检闸恒未命中——AUTO 链路确定性验证（抽检函数另有单测）。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class NeverSampledGate {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.leyon.lumin.moderation.service.AutoSampleGate neverSampled() {
            return caseId -> false;
        }
    }

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
        // 审核员替身建档 + role_assignment 绑定（V2 表已建；授权链路 V2 批次接线）
        seedReviewer(MySqlFixtures.REVIEWER_L1A, "sub-reviewer-l1a");
        seedReviewer(MySqlFixtures.REVIEWER_L2A, "sub-reviewer-l2a");
    }

    private static final String L1A = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.REVIEWER_L1A;

    private void seedReviewer(String userId, String oidcSubject) {
        jdbc.update("""
                INSERT IGNORE INTO user_profile (id, oidc_issuer, oidc_subject, nickname,
                    declared_age_band, verified_age_band, effective_age_band, account_status,
                    policy_revision, created_at, updated_at, created_by, updated_by)
                VALUES (?, 'https://id.example', ?, '审核员', 'C3', 'C3', 'C3', 'ACTIVE',
                    1, ?, ?, ?, ?)
                """, userId, oidcSubject, MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM);
        String grantId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT IGNORE INTO role_grant (id, target_user_id, role, scope_type,
                    scope_id, expires_at, reason, state, requested_by, reviewed_by,
                    reviewed_at, created_at, updated_at, created_by, updated_by, lock_version)
                VALUES (?, ?, 'REVIEWER_L1', 'GLOBAL', NULL, NULL, '测试夹具授予',
                    'APPROVED', 'system', 'system', ?, ?, ?, ?, 'system', 1)
                """, grantId, userId, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.BUSINESS_TIME);
        jdbc.update("""
                INSERT IGNORE INTO role_assignment (id, user_id, role, scope_type, grant_id,
                    created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, 'REVIEWER_L1', 'GLOBAL', ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), userId, grantId,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM);
    }

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(MvcResult result, String name)
            throws java.io.UnsupportedEncodingException {
        return com.leyon.lumin.support.TestResponseSupport.field(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8), name);
    }

    /** 提交 + 投递 + LOW 报告（路由人工）→ 返回 (workflowId, caseId, versionId)。 */
    private String[] submittedAndRoutedToHuman(String title) throws Exception {
        String workId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/works").header("Idempotency-Key", uuidKey())
                                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String draftId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/drafts").header("Idempotency-Key", uuidKey())
                                .contentType("application/json")
                                .content("{\"workId\":\"" + workId + "\",\"title\":\"" + title
                                        + "\",\"synopsis\":\"简介\",\"categoryId\":\""
                                        + MySqlFixtures.CATEGORY_LITERATURE + "\",\"tags\":[]}"))
                .andExpect(status().isOk()), "id");
        String workflowId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/drafts/" + draftId + "/submissions")
                                .header("Idempotency-Key", uuidKey())
                                .header("If-Match", "\"1\"")
                                .contentType("application/json").content("{}"))
                .andExpect(status().isAccepted()), "workflowId");
        dispatcher.deliverPending();
        String caseId = jdbc.queryForObject(
                "SELECT case_id FROM publication_workflow WHERE id = ?", String.class, workflowId);
        String versionId = jdbc.queryForObject(
                "SELECT version_id FROM publication_workflow WHERE id = ?", String.class, workflowId);
        String materialSha = jdbc.queryForObject(
                "SELECT material_sha256 FROM content_version WHERE id = ?", String.class, versionId);
        sendReport(caseId, workflowId, versionId, materialSha, "MEDIUM", "PASS", "G", "0.99");
        return new String[]{workId, draftId, workflowId, caseId, versionId};
    }

    private void sendReport(String caseId, String workflowId, String versionId,
                            String materialSha, String risk, String decision, String grade,
                            String confidence) throws Exception {
        mockMvc.perform(post("/internal/v1/machine-reports")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("""
                                {"caseId":"%s","workflowId":"%s","versionId":"%s","policyVersion":1,
                                 "materialSha256":"%s","modelVersion":"guard-v1","risk":"%s","riskFlags":[],
                                 "suggestedDecision":"%s",%s"confidence":%s,"evidenceRefs":["ev:1"],
                                 "inputTokens":100,"outputTokens":10,"durationMs":1000}
                                """.formatted(caseId, workflowId, versionId, materialSha, risk,
                                decision, grade == null ? "" : "\"grade\":\"" + grade + "\",",
                                confidence)))
                .andExpect(status().isOk());
    }

    @Test
    void reviewerClaimsAndApprovesThroughHumanFinalPass() throws Exception {
        String[] ctx = submittedAndRoutedToHuman("人审链作品");
        String caseId = ctx[3];
        String versionId = ctx[4];
        // 非审核员替身领取 → 403/A2001（03 §2.4 角色校验骨架口径）
        mockMvc.perform(post("/api/v1/review-cases/" + caseId + "/claims")
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A2001"));
        // 审核员领取 → CLAIMED + 领取人回填
        mockMvc.perform(post("/api/v1/review-cases/" + caseId + "/claims")
                        .header("X-Lumin-Demo-Subject", L1A)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("CLAIMED"));
        assertThat(jdbc.queryForObject(
                "SELECT assignee_id FROM review_case WHERE id = ?", String.class, caseId))
                .isEqualTo(com.leyon.lumin.foundation.web.DemoAuthenticationFilter.REVIEWER_L1A_SUBJECT);
        // 人审最终裁决（HUMAN_FINAL_PASS → APPROVED）+ 指针 + ContentPublished
        mockMvc.perform(post("/api/v1/review-cases/" + caseId + "/decisions")
                        .header("X-Lumin-Demo-Subject", L1A)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"2\"")
                        .contentType("application/json")
                        .content("""
                                {"decision":"PASS","grade":"G","reasonCode":"OK_1001",
                                 "reason":"内容合规","evidenceRefs":["ev:1"]}""" ))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("DECIDED"));
        dispatcher.deliverPending();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM content_version WHERE id = ?", String.class, versionId))
                .isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM publication_pointer WHERE target_type = 'WORK' "
                        + "AND target_id = ?", Integer.class, ctx[0])).isEqualTo(1);
        // 读者链：详情 200 + 正文未发布前（无章节）404 由作品维度覆盖
        mockMvc.perform(get("/api/v1/works/" + ctx[0]))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("人审链作品"))
                .andExpect(jsonPath("$.data.grade").value("G"));
    }

    @Test
    void staleIfMatchOnDecisionIs412() throws Exception {
        submittedAndRoutedToHuman("乐观锁作品");
        String caseId = jdbc.queryForObject(
                "SELECT id FROM review_case WHERE state = 'QUEUED' LIMIT 1", String.class);
        mockMvc.perform(post("/api/v1/review-cases/" + caseId + "/claims")
                        .header("X-Lumin-Demo-Subject", L1A)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        // 旧 If-Match（领取后 lock 已 +1）→ 412/B2002
        mockMvc.perform(post("/api/v1/review-cases/" + caseId + "/decisions")
                        .header("X-Lumin-Demo-Subject", L1A)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"decision\":\"PASS\",\"grade\":\"G\",\"reasonCode\":\"OK_1001\","
                                + "\"reason\":\"合规\",\"evidenceRefs\":[\"ev:1\"]}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("B2002"));
    }

    @Test
    void highRiskFirstReviewDoesNotDecide() throws Exception {
        String workId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/works").header("Idempotency-Key", uuidKey())
                                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String draftId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/drafts").header("Idempotency-Key", uuidKey())
                                .contentType("application/json")
                                .content("{\"workId\":\"" + workId + "\",\"title\":\"高风险\","
                                        + "\"synopsis\":\"简介\",\"categoryId\":\""
                                        + MySqlFixtures.CATEGORY_LITERATURE + "\",\"tags\":[]}"))
                .andExpect(status().isOk()), "id");
        mockMvc.perform(post("/api/v1/drafts/" + draftId + "/submissions")
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isAccepted());
        dispatcher.deliverPending();
        String caseId = jdbc.queryForObject(
                "SELECT id FROM review_case WHERE state = 'QUEUED' LIMIT 1", String.class);
        String versionId = jdbc.queryForObject(
                "SELECT version_id FROM review_case WHERE id = ?", String.class, caseId);
        String materialSha = jdbc.queryForObject(
                "SELECT material_sha256 FROM content_version WHERE id = ?",
                String.class, versionId);
        sendReport(caseId,
                jdbc.queryForObject(
                        "SELECT workflow_id FROM review_case WHERE id = ?", String.class, caseId),
                versionId, materialSha, "HIGH", "PASS", "G", "0.99");
        // 机审 HIGH → 必人审（T16），案件保持 QUEUED + risk/priority 更新（HIGH→P0）
        assertThat(jdbc.queryForObject(
                "SELECT risk FROM review_case WHERE id = ?", String.class, caseId))
                .isEqualTo("HIGH");
        assertThat(jdbc.queryForObject(
                "SELECT priority FROM review_case WHERE id = ?", String.class, caseId))
                .isEqualTo("P0");
        // 审核员首审裁决：HIGH 双审 → 首审不终局（FIRST_DECISION → WAITING_SECOND stage 2）
        mockMvc.perform(post("/api/v1/review-cases/" + caseId + "/claims")
                        .header("X-Lumin-Demo-Subject", L1A)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/review-cases/" + caseId + "/decisions")
                        .header("X-Lumin-Demo-Subject", L1A)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"2\"")
                        .contentType("application/json")
                        .content("{\"decision\":\"PASS\",\"grade\":\"G\",\"reasonCode\":\"OK_1001\","
                                + "\"reason\":\"首审合规\",\"evidenceRefs\":[\"ev:1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("WAITING_SECOND"))
                .andExpect(jsonPath("$.data.stage").value(2));
    }

    @Test
    void readerChainWorksAfterPublication() throws Exception {
        String workId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/works").header("Idempotency-Key", uuidKey())
                                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String draftId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/drafts").header("Idempotency-Key", uuidKey())
                                .contentType("application/json")
                                .content("{\"workId\":\"" + workId + "\",\"title\":\"读者可见\","
                                        + "\"synopsis\":\"简介\",\"categoryId\":\""
                                        + MySqlFixtures.CATEGORY_LITERATURE + "\",\"tags\":[]}"))
                .andExpect(status().isOk()), "id");
        submitAndApprove(workId, draftId);
        // 发布前章节未发布 → 正文 404（B2004）
        mockMvc.perform(get("/api/v1/chapters/" + UUID.randomUUID() + "/content"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("B2004"));
        // 发布后详情 200
        mockMvc.perform(get("/api/v1/works/" + workId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("读者可见"));
        // 列表含该作品
        mockMvc.perform(get("/api/v1/works"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].workId").value(workId));
    }

    @Test
    void chapterCatalogAndContentVisibleAfterPublish() throws Exception {
        String workId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/works").header("Idempotency-Key", uuidKey())
                                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String workDraft = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/drafts").header("Idempotency-Key", uuidKey())
                                .contentType("application/json")
                                .content("{\"workId\":\"" + workId + "\",\"title\":\"目录作品\","
                                        + "\"synopsis\":\"简介\",\"categoryId\":\""
                                        + MySqlFixtures.CATEGORY_LITERATURE + "\",\"tags\":[]}"))
                .andExpect(status().isOk()), "id");
        submitAndApprove(workId, workDraft);
        // 父作品已发布 → 章节走 AUTO 链（03 §2.3 前提满足）；发布推进过 work 乐观锁
        long workLock = jdbc.queryForObject(
                "SELECT lock_version FROM work WHERE id = ?", Long.class, workId);
        String chapterId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/works/" + workId + "/chapters")
                                .header("Idempotency-Key", uuidKey())
                                .header("If-Match", "\"" + workLock + "\"")
                                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String chapterDraft = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/drafts").header("Idempotency-Key", uuidKey())
                                .contentType("application/json")
                                .content("{\"workId\":\"" + workId + "\",\"chapterId\":\"" + chapterId
                                        + "\",\"title\":\"第一章\",\"body\":\"第一段\\n第二段\"}"))
                .andExpect(status().isOk()), "id");
        submitAndApprove(workId, chapterDraft);
        // 章节目录：仅含已发布章节，sequenceNo 排序
        mockMvc.perform(get("/api/v1/works/" + workId + "/chapters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(chapterId))
                .andExpect(jsonPath("$.data[0].sequenceNo").value(1));
        // 正文：返回已发布版本纯文本
        mockMvc.perform(get("/api/v1/chapters/" + chapterId + "/content"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.body").value("第一段\n第二段"));
        // 未发布章节目录为空（未发布作品维度 404 由 readerChainWorksAfterPublication 覆盖）
        String otherWork = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/works").header("Idempotency-Key", uuidKey())
                                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        mockMvc.perform(get("/api/v1/works/" + otherWork + "/chapters"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("B2004"));
    }

    @Test
    void reviewerFinalPassWithoutParentWorkDoesNotPublishChapter() throws Exception {
        // 03 §2.3：父作品无可见已通过版本 → 人审终局 PASS 也被状态机 PARENT_APPROVED 拒绝
        String workId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/works").header("Idempotency-Key", uuidKey())
                                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String chapterId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/works/" + workId + "/chapters")
                                .header("Idempotency-Key", uuidKey())
                                .header("If-Match", "\"1\"")
                                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String chapterDraft = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/drafts").header("Idempotency-Key", uuidKey())
                                .contentType("application/json")
                                .content("{\"workId\":\"" + workId + "\",\"chapterId\":\"" + chapterId
                                        + "\",\"title\":\"孤儿章\",\"body\":\"正文\"}"))
                .andExpect(status().isOk()), "id");
        mockMvc.perform(post("/api/v1/drafts/" + chapterDraft + "/submissions")
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isAccepted());
        dispatcher.deliverPending();
        String workflowId = jdbc.queryForObject(
                "SELECT id FROM publication_workflow WHERE version_id = (SELECT id FROM "
                        + "content_version WHERE source_draft_id = ?)", String.class, chapterDraft);
        String caseId = jdbc.queryForObject(
                "SELECT case_id FROM publication_workflow WHERE id = ?", String.class, workflowId);
        String versionId = jdbc.queryForObject(
                "SELECT version_id FROM publication_workflow WHERE id = ?", String.class, workflowId);
        String materialSha = jdbc.queryForObject(
                "SELECT material_sha256 FROM content_version WHERE id = ?", String.class, versionId);
        // 机审 MEDIUM：路由人工队列（案件保持 QUEUED；父作品门禁使流程停留 HUMAN_REVIEW）
        sendReport(caseId, workflowId, versionId, materialSha, "MEDIUM", "PASS", "G", "0.99");
        dispatcher.deliverPending();
        // 审核员终局 PASS → 案件 DECIDED，但发布事件被 PARENT_APPROVED 拒绝
        mockMvc.perform(post("/api/v1/review-cases/" + caseId + "/claims")
                        .header("X-Lumin-Demo-Subject", L1A)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/review-cases/" + caseId + "/decisions")
                        .header("X-Lumin-Demo-Subject", L1A)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"2\"")
                        .contentType("application/json")
                        .content("{\"decision\":\"PASS\",\"grade\":\"G\",\"reasonCode\":\"OK_1001\","
                                + "\"reason\":\"合规\",\"evidenceRefs\":[\"ev:1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("DECIDED"));
        dispatcher.deliverPending();
        // 无章节指针、业务态停留 DRAFT、流程停留 HUMAN_REVIEW + 拒绝审计
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM publication_pointer WHERE target_type = 'CHAPTER' "
                        + "AND target_id = ?", Integer.class, chapterId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT business_state FROM chapter WHERE id = ?", String.class, chapterId))
                .isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject(
                "SELECT state FROM publication_workflow WHERE id = ?", String.class, workflowId))
                .isEqualTo("HUMAN_REVIEW");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_record WHERE action = 'review_resolved.rejected'",
                Integer.class)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void meWorkspaceContractPathsServeAuthorLists() throws Exception {
        // 05 §3.2 契约路径：GET /me/works、GET /me/works/{id}/chapters（增量十七对齐）
        String workId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/works").header("Idempotency-Key", uuidKey())
                                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        mockMvc.perform(post("/api/v1/drafts").header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"title\":\"契约路径\","
                                + "\"synopsis\":\"简介\",\"categoryId\":\""
                                + MySqlFixtures.CATEGORY_LITERATURE + "\",\"tags\":[]}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me/works"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(workId))
                .andExpect(jsonPath("$.data.items[0].draftState").value("EDITABLE"));
        long workLock = jdbc.queryForObject(
                "SELECT lock_version FROM work WHERE id = ?", Long.class, workId);
        String chapterId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/works/" + workId + "/chapters")
                                .header("Idempotency-Key", uuidKey())
                                .header("If-Match", "\"" + workLock + "\"")
                                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        mockMvc.perform(post("/api/v1/drafts").header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"chapterId\":\"" + chapterId
                                + "\",\"title\":\"第一章\",\"body\":\"正文\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me/works/" + workId + "/chapters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(chapterId))
                .andExpect(jsonPath("$.data.items[0].state").value("DRAFT"))
                .andExpect(jsonPath("$.data.items[0].draftState").value("EDITABLE"));
    }

    private void submitAndApprove(String workId, String draftId) throws Exception {
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
        // 机审 AUTO_ACCEPT（ReviewResolved 事件）→ 消费后发布
        sendReport(caseId, workflowId, versionId, materialSha, "LOW", "PASS", "G", "0.99");
        dispatcher.deliverPending();
    }
}
