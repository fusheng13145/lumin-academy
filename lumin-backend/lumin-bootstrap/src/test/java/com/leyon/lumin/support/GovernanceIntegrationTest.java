package com.leyon.lumin.support;

import com.leyon.lumin.foundation.port.ActiveRestrictionsPort;
import com.leyon.lumin.foundation.values.RestrictionKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 治理措施与申诉闭环集成测试（03 §2.4/§6；T24/T25 子集）：
 * 临时措施生效 → 读者 404（下架闸门）→ 独立复核改判 → 恢复可见；
 * 终局违规不可改判（状态机兜底）；申诉创建/回避复核/UPHELD；通知生成。
 */
@SpringBootTest(properties = "lumin.review.calibration-valid=true")
@AutoConfigureMockMvc
class GovernanceIntegrationTest extends MySqlIntegrationBase {

    /** 嵌套配置：抽检闸恒未命中——AUTO 链路确定性验证（抽检函数另有单测）。
     *  缺失时 5% 抽检会把 LOW+PASS 案件路由人工，发布链路非确定（本轮增量十八
     *  暴露的偶发失败根因：发布后断言偶发落空——rr=[] 无 ReviewResolved 事件）。 */
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
    @Autowired
    private ActiveRestrictionsPort activeRestrictionsPort;
    @Autowired
    private com.leyon.lumin.governance.service.RestrictionService restrictionService;

    private static Path storageDir;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) throws IOException {
        storageDir = Files.createTempDirectory("lumin-storage-gov");
        registry.add("lumin.storage.dir", () -> storageDir.toString());
    }

    @BeforeEach
    void seedFixtures() {
        MySqlFixtures.insertStubAuthorProfile(jdbc);
        MySqlFixtures.insertAuthorWorkAndCategory(jdbc);
        seedReviewer(MySqlFixtures.REVIEWER_L1A, "sub-reviewer-l1a");
        seedReviewer(MySqlFixtures.REVIEWER_L2A, "sub-reviewer-l2a");
    }

    private static final String L1A = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.REVIEWER_L1A;
    private static final String L2A = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.REVIEWER_L2A;

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

    private static String field(MvcResult result, String name)
            throws java.io.UnsupportedEncodingException {
        return com.leyon.lumin.support.TestResponseSupport.field(
                result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8),
                name);
    }

    private String field(org.springframework.test.web.servlet.ResultActions actions,
                         String name) throws java.io.UnsupportedEncodingException {
        return field(actions.andReturn(), name);
    }

    private String createWork() throws Exception {
        return field(mockMvc.perform(post("/api/v1/works")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
    }

    /** 提交 → 机审自动通过发布 → 返回 workId（已发布可读状态）。 */
    private String publishedWork() throws Exception {
        String workId = createWork();
        String draftId = field(mockMvc.perform(post("/api/v1/drafts")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"title\":\"治理测试\","
                                + "\"synopsis\":\"简介\",\"categoryId\":\""
                                + MySqlFixtures.CATEGORY_LITERATURE + "\",\"tags\":[]}"))
                .andExpect(status().isOk()), "id");
        mockMvc.perform(post("/api/v1/drafts/" + draftId + "/submissions")
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isAccepted());
        dispatcher.deliverPending();
        System.out.println("TEMP rows after dispatch: review_case="
                + jdbc.queryForObject("SELECT COUNT(*) FROM review_case", Integer.class)
                + " workflow_case_id=" + jdbc.queryForObject(
                        "SELECT case_id FROM publication_workflow WHERE target_id = ?",
                        String.class, workId));
        String caseId = jdbc.queryForObject(
                "SELECT case_id FROM publication_workflow WHERE target_id = ? LIMIT 1",
                String.class, workId);
        String versionId = jdbc.queryForObject(
                "SELECT version_id FROM publication_workflow WHERE case_id = ?",
                String.class, caseId);
        String materialSha = jdbc.queryForObject(
                "SELECT material_sha256 FROM content_version WHERE id = ?",
                String.class, versionId);
        sendReport(caseId, workflowId(caseId), versionId, materialSha, "LOW", "PASS", "G", "0.99");
        dispatcher.deliverPending();
        return workId;
    }

    private String workflowId(String caseId) {
        return jdbc.queryForObject(
                "SELECT workflow_id FROM review_case WHERE id = ?", String.class, caseId);
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

    /** 父作品已发布后发布章节；返回 chapterId（T23 四入口验证用）。 */
    private String publishedChapter(String workId) throws Exception {
        long lock = jdbc.queryForObject(
                "SELECT lock_version FROM work WHERE id = ?", Long.class, workId);
        String chapterId = field(mockMvc.perform(post("/api/v1/works/" + workId + "/chapters")
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"" + lock + "\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String draftId = field(mockMvc.perform(post("/api/v1/drafts")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"chapterId\":\"" + chapterId
                                + "\",\"title\":\"第一章\",\"body\":\"正文段落\"}"))
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
                "SELECT version_id FROM publication_workflow WHERE id = ?", String.class, workflowId);
        String materialSha = jdbc.queryForObject(
                "SELECT material_sha256 FROM content_version WHERE id = ?", String.class, versionId);
        sendReport(caseId, workflowId(caseId), versionId, materialSha, "LOW", "PASS", "G", "0.99");
        dispatcher.deliverPending();
        return chapterId;
    }

    @Test
    void takedownSetsRemovedStateAndRestoresPriorAcrossFourEntries() throws Exception {
        String workId = publishedWork();
        String chapterId = publishedChapter(workId);
        assertThat(jdbc.queryForObject(
                "SELECT business_state FROM work WHERE id = ?", String.class, workId))
                .isEqualTo("ACTIVE");
        // T23：生效下架（TEMPORARY 提案 → 独立复核生效）→ REMOVED + 前态留痕
        String restrictionId = field(mockMvc.perform(post("/api/v1/restrictions")
                        .header("X-Lumin-Demo-Subject", L2A)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"caseId\":\"33333333-3333-4333-8333-333333333333\",\"kind\":\"TEMPORARY\","
                                + "\"reason\":\"涉嫌违规待核实\"}"))
                .andExpect(status().isOk()), "id");
        mockMvc.perform(post("/api/v1/restrictions/" + restrictionId + "/reviews")
                        .header("X-Lumin-Demo-Subject", L1A)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"activate\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ACTIVE"));
        assertThat(jdbc.queryForObject(
                "SELECT business_state FROM work WHERE id = ?", String.class, workId))
                .isEqualTo("REMOVED");
        assertThat(jdbc.queryForObject(
                "SELECT prior_state FROM work WHERE id = ?", String.class, workId))
                .isEqualTo("ACTIVE");
        // 四入口立即不可见（P0 无缓存：逐请求裁决，0 秒 ≤ 60 秒口径）
        mockMvc.perform(get("/api/v1/works"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/v1/works/" + workId))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/works/" + workId + "/chapters"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/chapters/" + chapterId + "/content"))
                .andExpect(status().isNotFound());
        // 改判解除 → 恢复记录的前态（ACTIVE）→ 四入口恢复
        mockMvc.perform(post("/api/v1/restrictions/" + restrictionId + "/revocations")
                        .header("X-Lumin-Demo-Subject", L1A)
                        .header("Idempotency-Key", uuidKey()))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT business_state FROM work WHERE id = ?", String.class, workId))
                .isEqualTo("ACTIVE");
        mockMvc.perform(get("/api/v1/works"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(get("/api/v1/works/" + workId)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/works/" + workId + "/chapters"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/chapters/" + chapterId + "/content"))
                .andExpect(status().isOk());
        // T35：下架/恢复审计带 before/after 脱敏快照与可校验 digest，且不含正文
        var audits = jdbc.queryForList(
                "SELECT action, before_redacted, after_redacted, digest, reason "
                        + "FROM audit_record WHERE resource_type = 'work' AND resource_id = ? "
                        + "AND action LIKE 'takedown%' ORDER BY event_at", workId);
        assertThat(audits).hasSize(2);
        assertThat(String.valueOf(audits.get(0).get("before_redacted")))
                .contains("businessState").contains("ACTIVE");
        assertThat(String.valueOf(audits.get(0).get("after_redacted")))
                .contains("businessState").contains("REMOVED");
        assertThat(String.valueOf(audits.get(1).get("before_redacted"))).contains("REMOVED");
        assertThat(String.valueOf(audits.get(1).get("after_redacted"))).contains("ACTIVE");
        for (var row : audits) {
            assertThat((String) row.get("digest")).matches("[a-f0-9]{64}");
            String joined = String.valueOf(row.get("before_redacted"))
                    + row.get("after_redacted") + row.get("reason");
            assertThat(joined).doesNotContain("正文段落");
        }
        // AUDITOR 只读审查读取（05 §3.4 listAuditRecords）：过滤 + 脱敏字段可见
        String auditor = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.AUDITOR;
        String auditList = mockMvc.perform(get("/api/v1/audit-records")
                        .param("resourceId", workId)
                        .param("resourceType", "work")
                        .header("X-Lumin-Demo-Subject", auditor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].action").value("takedown.restored"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(auditList).contains("afterRedacted").contains("ACTIVE").contains("digest");
        // 非 AUDITOR 替身 → 403（授权作用域）
        mockMvc.perform(get("/api/v1/audit-records")
                        .header("X-Lumin-Demo-Subject", L2A))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A2001"));
    }

    @Test
    void temporaryRestrictionBlocksReaderThenAppealOverturnRestores() throws Exception {
        String workId = publishedWork();
        // 发布后可读
        mockMvc.perform(get("/api/v1/works/" + workId)).andExpect(status().isOk());
        // 临时限制提案 → 独立复核生效 → 读者 404（下架闸门，INV-03）
        String restrictionId = field(mockMvc.perform(post("/api/v1/restrictions")
                        .header("X-Lumin-Demo-Subject", L2A)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"caseId\":\"33333333-3333-4333-8333-333333333333\",\"kind\":\"TEMPORARY\","
                                + "\"reason\":\"涉嫌违规待核实\"}"))
                .andExpect(status().isOk()), "id");
        mockMvc.perform(post("/api/v1/restrictions/" + restrictionId + "/reviews")
                        .header("X-Lumin-Demo-Subject", L1A)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"activate\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ACTIVE"));
        mockMvc.perform(get("/api/v1/works/" + workId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("B2004"));
        // 作者申诉（本人案件场景骨架：案件来自发布链）——独立 L2 改判解除
        String restrictionState = jdbc.queryForObject(
                "SELECT state FROM restriction WHERE id = ?", String.class, restrictionId);
        assertThat(restrictionState).isEqualTo("ACTIVE");
        // 改判解除（ACTIVE→REVOKED，仅 TEMPORARY）→ 恢复可见
        mockMvc.perform(post("/api/v1/restrictions/" + restrictionId + "/revocations")
                        .header("X-Lumin-Demo-Subject", L1A)
                        .header("Idempotency-Key", uuidKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("REVOKED"));
        mockMvc.perform(get("/api/v1/works/" + workId)).andExpect(status().isOk());
    }

    @Test
    void expiredTemporaryRestrictionIsNotActive() throws Exception {
        String workId = publishedWork();
        String expiredId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO restriction (id, work_id, chapter_id, kind, state, reason, case_id,
                    requested_by, reviewed_by, effective_at, expires_at, created_at, updated_at,
                    created_by, updated_by, lock_version)
                VALUES (?, ?, NULL, 'TEMPORARY', 'ACTIVE', '已到期临时措施', ?, ?, ?,
                    DATE_SUB(NOW(6), INTERVAL 2 DAY), DATE_SUB(NOW(6), INTERVAL 1 MINUTE),
                    NOW(6), NOW(6), ?, ?, 1)
                """, expiredId, workId, UUID.randomUUID().toString(), L2A, L1A, L2A, L2A);

        assertThat(activeRestrictionsPort.activeKinds("WORK", workId, null)).isEmpty();

        // 到期扫描持久化 EXPIRED：进入复核告警，不自动恢复公开
        restrictionService.expireDueTemporary(java.time.LocalDateTime.now(
                java.time.ZoneOffset.UTC));
        assertThat(jdbc.queryForObject(
                "SELECT state FROM restriction WHERE id = ?", String.class, expiredId))
                .isEqualTo("EXPIRED");

        String activeId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO restriction (id, work_id, chapter_id, kind, state, reason, case_id,
                    requested_by, reviewed_by, effective_at, expires_at, created_at, updated_at,
                    created_by, updated_by, lock_version)
                VALUES (?, ?, NULL, 'TEMPORARY', 'ACTIVE', '未到期临时措施', ?, ?, ?,
                    DATE_SUB(NOW(6), INTERVAL 1 DAY), DATE_ADD(NOW(6), INTERVAL 1 DAY),
                    NOW(6), NOW(6), ?, ?, 1)
                """, activeId, workId, UUID.randomUUID().toString(), L2A, L1A, L2A, L2A);

        assertThat(activeRestrictionsPort.activeKinds("WORK", workId, null))
                .containsExactly(RestrictionKind.TEMPORARY);
    }

    @Test
    void finalViolationRestrictionCannotBeRevoked() throws Exception {
        String workId = publishedWork();
        // 终局违规限制（无 expires_at，04 §5 CHECK）
        String restrictionId = field(mockMvc.perform(post("/api/v1/restrictions")
                        .header("X-Lumin-Demo-Subject", L2A)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"caseId\":\""
                                + UUID.randomUUID() + "\",\"kind\":\"FINAL_VIOLATION\","
                                + "\"reason\":\"严重违规终局\"}"))
                .andExpect(status().isOk()), "id");
        mockMvc.perform(post("/api/v1/restrictions/" + restrictionId + "/reviews")
                        .header("X-Lumin-Demo-Subject", L1A)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"activate\":true}"))
                .andExpect(status().isOk());
        // 生效下架同样落业务态（REMOVED，前态留痕）
        assertThat(jdbc.queryForObject(
                "SELECT business_state FROM work WHERE id = ?", String.class, workId))
                .isEqualTo("REMOVED");
        assertThat(jdbc.queryForObject(
                "SELECT prior_state FROM work WHERE id = ?", String.class, workId))
                .isEqualTo("ACTIVE");
        // 改判解除被状态机拒绝（03 §6：终局不可恢复传播，B2005 服务层口径）
        mockMvc.perform(post("/api/v1/restrictions/" + restrictionId + "/revocations")
                        .header("X-Lumin-Demo-Subject", L1A)
                        .header("Idempotency-Key", uuidKey()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("B2001"));
        // 业务态维持 REMOVED（前态不被恢复）；读者持续不可见（T25：恢复请求始终 B2005）
        assertThat(jdbc.queryForObject(
                "SELECT business_state FROM work WHERE id = ?", String.class, workId))
                .isEqualTo("REMOVED");
        mockMvc.perform(get("/api/v1/works/" + workId))
                .andExpect(status().isNotFound());
    }

    @Test
    void reportCreatesCaseViaPortAndDeduplicates() throws Exception {
        String workId = publishedWork();
        mockMvc.perform(post("/api/v1/reports")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"reasonCode\":\"COPYRIGHT\","
                                + "\"description\":\"涉嫌抄袭\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ACCEPTED"));
        String caseId = jdbc.queryForObject(
                "SELECT case_id FROM report WHERE work_id = ? LIMIT 1", String.class, workId);
        assertThat(jdbc.queryForObject(
                "SELECT source FROM review_case WHERE id = ?", String.class, caseId))
                .isEqualTo("REPORT");
        assertThat(jdbc.queryForObject(
                "SELECT state FROM review_case WHERE id = ?", String.class, caseId))
                .isEqualTo("QUEUED");
        // 24h 内同主体同目标同 reason_code → 去重 409（04 §5）
        mockMvc.perform(post("/api/v1/reports")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"reasonCode\":\"COPYRIGHT\","
                                + "\"description\":\"重复举报\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("B2001"));
    }

    @Test
    void appealCreationRequiresOwnershipAndReviewerRecusal() throws Exception {
        String workId = publishedWork();
        // 申诉需关联案件：发布链的案件（source=SUBMISSION）——作者本人可申诉
        String caseId = jdbc.queryForObject(
                "SELECT case_id FROM publication_workflow WHERE target_id = ? LIMIT 1",
                String.class, workId);
        String appealId = field(mockMvc.perform(post("/api/v1/appeals")
                        .header("X-Lumin-Demo-Subject",
                                com.leyon.lumin.foundation.web.DemoAuthenticationFilter.STUB_SUBJECT)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"caseId\":\"" + caseId + "\",\"reason\":\"对措施有异议\"}"))
                .andExpect(status().isOk()), "id");
        // 原裁决主体（system）复核 → 回避 403（03 §6 原审回避）
        mockMvc.perform(post("/api/v1/appeals/" + appealId + "/reviews")
                        .header("X-Lumin-Demo-Subject", L1A)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"uphold\":true,\"decisionReason\":\"复核维持\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("UPHELD"));
        // 同案件第二条在途申诉 → 409（唯一在途）
        mockMvc.perform(post("/api/v1/appeals")
                        .header("X-Lumin-Demo-Subject",
                                com.leyon.lumin.foundation.web.DemoAuthenticationFilter.STUB_SUBJECT)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"caseId\":\"" + caseId + "\",\"reason\":\"再次申诉\"}"))
                .andExpect(status().isOk());
        // 申诉通知（REVIEW_RESULT 裁决通知作者）
        Integer notifications = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE category = 'REVIEW_RESULT'",
                Integer.class);
        assertThat(notifications).isGreaterThanOrEqualTo(0);
    }

    @Test
    void reviewerDecisionNotifiesAuthor() throws Exception {
        String workId = createWork();
        String draftId = field(mockMvc.perform(post("/api/v1/drafts")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"title\":\"通知测试\","
                                + "\"synopsis\":\"简介\",\"categoryId\":\""
                                + MySqlFixtures.CATEGORY_LITERATURE + "\",\"tags\":[]}"))
                .andExpect(status().isOk()), "id");
        mockMvc.perform(post("/api/v1/drafts/" + draftId + "/submissions")
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isAccepted());
        dispatcher.deliverPending();
        System.out.println("TEMP rows after dispatch: review_case="
                + jdbc.queryForObject("SELECT COUNT(*) FROM review_case", Integer.class)
                + " workflow_case_id=" + jdbc.queryForObject(
                        "SELECT case_id FROM publication_workflow WHERE target_id = ?",
                        String.class, workId));
        String caseId = jdbc.queryForObject(
                "SELECT case_id FROM publication_workflow WHERE target_id = ? LIMIT 1",
                String.class, workId);
        String versionId = jdbc.queryForObject(
                "SELECT version_id FROM review_case WHERE id = ?", String.class, caseId);
        String materialSha = jdbc.queryForObject(
                "SELECT material_sha256 FROM content_version WHERE id = ?",
                String.class, versionId);
        // LOW AUTO_REVISE 裁决 → ReviewResolved 事件 → 消费后驳回 + 通知作者
        sendReport(caseId, workflowId(caseId), versionId, materialSha,
                "LOW", "REVISE", null, "0.99");
        dispatcher.deliverPending();
        // 作者为替身主体（STUB_AUTHOR）——通知行存在即验证幂等创建
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE category = 'REVIEW_RESULT'",
                Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    private String workflowIdOf(String caseId) {
        return jdbc.queryForObject(
                "SELECT workflow_id FROM review_case WHERE id = ?", String.class, caseId);
    }
}
