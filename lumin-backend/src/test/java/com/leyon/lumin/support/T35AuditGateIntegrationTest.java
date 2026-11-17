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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T35 审计写失败闸门集成测试（07 T35；03 §6 INV-06）：
 * 高危操作（治理措施生效）依赖审计同事务写入——审计写失败时整个事务回滚，
 * 治理措施不生效、业务态不迁移（「审计写失败后高危操作回滚」）。
 * 选择性失败桩：仅 restriction.* 审计抛出，其余审计跳过（发布链不受影响）。
 */
@SpringBootTest(properties = "lumin.review.calibration-valid=true")
@AutoConfigureMockMvc
class T35AuditGateIntegrationTest extends MySqlIntegrationBase {

    private static final String L1A = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.REVIEWER_L1A;
    private static final String L2A = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.REVIEWER_L2A;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.leyon.lumin.foundation.event.OutboxDispatcher dispatcher;

    /** 嵌套配置：抽检闸恒未命中 + 选择性审计失败桩（restriction.* 抛出）。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class TestGates {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.leyon.lumin.moderation.service.AutoSampleGate neverSampled() {
            return caseId -> false;
        }

        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.leyon.lumin.foundation.audit.AuditRecorder failingOnRestriction() {
            return new com.leyon.lumin.foundation.audit.AuditRecorder() {
                @Override
                public void record(AuditEntry entry) {
                    // 仅措施生效链（激活/下架业务态迁移）审计失败；提案审计正常
                    if (entry.action().startsWith("restriction.activated")
                            || entry.action().startsWith("takedown.")) {
                        throw new IllegalStateException("审计归档不可用（模拟）");
                    }
                }

                @Override
                public void recordTransition(AuditEntry entry,
                                             java.util.Map<String, String> before,
                                             java.util.Map<String, String> after) {
                    record(entry);
                }
            };
        }
    }

    @BeforeEach
    void seedFixtures() {
        MySqlFixtures.insertStubAuthorProfile(jdbc);
        MySqlFixtures.insertAuthorWorkAndCategory(jdbc);
        jdbc.update("""
                INSERT IGNORE INTO user_profile (id, oidc_issuer, oidc_subject, nickname,
                    declared_age_band, verified_age_band, effective_age_band, account_status,
                    policy_revision, created_at, updated_at, created_by, updated_by)
                VALUES (?, 'https://id.example', 'sub-reviewer-l1a', '审核员', 'C3', 'C3', 'C3',
                    'ACTIVE', 1, NOW(6), NOW(6), 'system', 'system')
                """, MySqlFixtures.REVIEWER_L1A);
        jdbc.update("""
                INSERT IGNORE INTO user_profile (id, oidc_issuer, oidc_subject, nickname,
                    declared_age_band, verified_age_band, effective_age_band, account_status,
                    policy_revision, created_at, updated_at, created_by, updated_by)
                VALUES (?, 'https://id.example', 'sub-reviewer-l2a', '审核员', 'C3', 'C3', 'C3',
                    'ACTIVE', 1, NOW(6), NOW(6), 'system', 'system')
                """, MySqlFixtures.REVIEWER_L2A);
    }

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(org.springframework.test.web.servlet.ResultActions actions,
                                String name) throws java.io.UnsupportedEncodingException {
        return com.leyon.lumin.support.TestResponseSupport.field(actions, name);
    }

    @Test
    void auditWriteFailureRollsBackHighRiskOperation() throws Exception {
        // 发布作品（本桩仅 restriction.* 审计失败，发布链正常）
        String workId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/works").header("Idempotency-Key", uuidKey())
                                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String draftId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/drafts").header("Idempotency-Key", uuidKey())
                                .contentType("application/json")
                                .content("{\"workId\":\"" + workId + "\",\"title\":\"审计闸\","
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
                "SELECT case_id FROM publication_workflow WHERE target_id = ? LIMIT 1",
                String.class, workId);
        String workflowId = jdbc.queryForObject(
                "SELECT workflow_id FROM review_case WHERE id = ?", String.class, caseId);
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
        // 作品已发布（业务态 ACTIVE）
        assertThat(jdbc.queryForObject(
                "SELECT business_state FROM work WHERE id = ?", String.class, workId))
                .isEqualTo("ACTIVE");
        // 高危操作：治理措施生效（审计写失败 → 事务回滚 → 措施不生效）
        String restrictionId = field(mockMvc.perform(post("/api/v1/restrictions")
                        .header("X-Lumin-Demo-Subject", L2A)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"kind\":\"TEMPORARY\","
                                + "\"reason\":\"涉嫌违规待核实\"}"))
                .andExpect(status().isOk()), "id");
        mockMvc.perform(post("/api/v1/restrictions/" + restrictionId + "/reviews")
                        .header("X-Lumin-Demo-Subject", L1A)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"activate\":true}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .isIn(500, 503));
        // 回滚断言：限制停留 PENDING、业务态未迁移、无下架审计
        assertThat(jdbc.queryForObject(
                "SELECT state FROM restriction WHERE id = ?", String.class, restrictionId))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT business_state FROM work WHERE id = ?", String.class, workId))
                .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_record WHERE resource_id = ? "
                        + "AND action LIKE 'takedown%'", Integer.class, workId)).isZero();
        // 读者不受影响（措施未生效 → 可读）
        mockMvc.perform(get("/api/v1/works/" + workId))
                .andExpect(status().isOk());
    }
}
