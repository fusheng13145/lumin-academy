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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UGC-004 角色授予链集成测试（docs/13；docs/04 §5.2 role_grant；6.8 互斥）：
 * 提案（OPERATOR）→ 自批拒绝（ADMIN 提案 ADMIN 审）→ 独立 ADMIN 审批 →
 * 绑定生效（被授予主体实际获得角色能力——领取审核案件）；互斥（已授 AUTHOR
 * 再授 REVIEWER_L1 → 400）；到期授予不产生有效角色；未授权主体领取 → 403。
 */
@SpringBootTest(properties = "lumin.review.calibration-valid=true")
@AutoConfigureMockMvc
class UGC004RoleGrantIntegrationTest extends MySqlIntegrationBase {

    private static final String OPERATOR = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.OPERATOR;
    private static final String ADMIN = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.ADMIN;
    private static final String AUTHOR_SUBJECT =
            com.leyon.lumin.foundation.web.DemoAuthenticationFilter.STUB_SUBJECT;
    private static final String REVIEWER_L1A_SUBJECT =
            com.leyon.lumin.foundation.web.DemoAuthenticationFilter.REVIEWER_L1A_SUBJECT;

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
        MySqlFixtures.insertConfigStubs(jdbc);
        // 审核员替身（自批/互斥用例需要其用户行）
        for (String[] stub : new String[][]{
                {MySqlFixtures.REVIEWER_L1A, "sub-reviewer-l1a"},
                {MySqlFixtures.REVIEWER_L2A, "sub-reviewer-l2a"}}) {
            jdbc.update("""
                    INSERT IGNORE INTO user_profile (id, oidc_issuer, oidc_subject, nickname,
                        declared_age_band, verified_age_band, effective_age_band, account_status,
                        policy_revision, created_at, updated_at, created_by, updated_by)
                    VALUES (?, 'https://id.example', ?, '审核员', 'C3', 'C3', 'C3', 'ACTIVE',
                        1, NOW(6), NOW(6), 'system', 'system')
                    """, stub[0], stub[1]);
        }
    }

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    private String propose(String subject, String header, String target, String role,
                           String expiresAt) throws Exception {
        StringBuilder body = new StringBuilder("{\"targetUserId\":\"").append(target)
                .append("\",\"role\":\"").append(role)
                .append("\",\"reason\":\"授予链测试\"");
        if (expiresAt != null) {
            body.append(",\"expiresAt\":\"").append(expiresAt).append("\"");
        }
        body.append("}");
        MvcResult result = mockMvc.perform(post("/api/v1/role-grants")
                        .header("X-Lumin-Demo-Subject", header)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content(body.toString()))
                .andExpect(status().isOk())
                .andReturn();
        return com.leyon.lumin.support.TestResponseSupport.field(result, "id");
    }

    private String review(String header, String grantId, boolean approve) throws Exception {
        return mockMvc.perform(post("/api/v1/role-grants/" + grantId + "/reviews")
                        .header("X-Lumin-Demo-Subject", header)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"decision\":\"" + (approve ? "APPROVE" : "REJECT") + "\"}"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 提交作品建审核案件（QUEUED），返回 caseId。 */
    private String queuedCase(String workId) throws Exception {
        String draftId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/drafts").header("Idempotency-Key", uuidKey())
                                .contentType("application/json")
                                .content("{\"workId\":\"" + workId + "\",\"title\":\"授予链\","
                                        + "\"synopsis\":\"简介\",\"categoryId\":\""
                                        + MySqlFixtures.CATEGORY_LITERATURE + "\",\"tags\":[]}"))
                .andExpect(status().isOk()), "id");
        mockMvc.perform(post("/api/v1/drafts/" + draftId + "/submissions")
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isAccepted());
        dispatcher.deliverPending();
        return jdbc.queryForObject(
                "SELECT case_id FROM publication_workflow WHERE version_id = (SELECT id FROM "
                        + "content_version WHERE source_draft_id = ?)", String.class, draftId);
    }

    @Test
    void grantChainConfersRoleCapabilityToGrantee() throws Exception {
        // AUTHOR 替身提交作品 → QUEUED 案件；未授权时 AUTHOR 领取 → 403
        String workId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/works").header("Idempotency-Key", uuidKey())
                                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String caseId = queuedCase(workId);
        mockMvc.perform(post("/api/v1/review-cases/" + caseId + "/claims")
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A2001"));
        // 授予链：OPERATOR 提案 REVIEWER_L1 → ADMIN 异人审批 → 绑定生效
        String grantId = propose(OPERATOR, "OPERATOR", AUTHOR_SUBJECT, "REVIEWER_L1", null);
        assertThat(review(ADMIN, grantId, true)).contains("\"state\":\"APPROVED\"");
        // 被授予主体现在能领取（角色能力实证：有效角色来自授予，而非客户端自报）
        mockMvc.perform(post("/api/v1/review-cases/" + caseId + "/claims")
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("CLAIMED"));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_assignment WHERE user_id = ? AND role = 'REVIEWER_L1' "
                        + "AND deleted_at IS NULL", Integer.class, AUTHOR_SUBJECT)).isEqualTo(1);
    }

    @Test
    void selfReviewExpiredGrantAndMutexAreRejected() throws Exception {
        // 自批拒绝：ADMIN 提案 → ADMIN 审批 → A2001
        String selfGrant = propose(ADMIN, "ADMIN", REVIEWER_L1A_SUBJECT, "REVIEWER_L2", null);
        assertThat(review(ADMIN, selfGrant, true)).contains("A2001");
        // 到期授予：expires_at 为过去 → APPROVED 但不产生有效角色（领取仍 403）
        String workId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/works").header("Idempotency-Key", uuidKey())
                                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String caseId = queuedCase(workId);
        String expiredGrant = propose(OPERATOR, "OPERATOR", AUTHOR_SUBJECT, "REVIEWER_L1",
                "2026-01-01T00:00:00");
        assertThat(review(ADMIN, expiredGrant, true)).contains("APPROVED");
        mockMvc.perform(post("/api/v1/review-cases/" + caseId + "/claims")
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        // 互斥：已授 AUTHOR 的主体再授 REVIEWER_L1 → 400（6.8 审核员禁止兼任作者）
        String authorGrant = propose(OPERATOR, "OPERATOR", REVIEWER_L1A_SUBJECT, "AUTHOR", null);
        assertThat(review(ADMIN, authorGrant, true)).contains("APPROVED");
        String reviewerGrant = propose(OPERATOR, "OPERATOR", REVIEWER_L1A_SUBJECT,
                "REVIEWER_L1", null);
        assertThat(review(ADMIN, reviewerGrant, true)).contains("P1001");
    }
}
