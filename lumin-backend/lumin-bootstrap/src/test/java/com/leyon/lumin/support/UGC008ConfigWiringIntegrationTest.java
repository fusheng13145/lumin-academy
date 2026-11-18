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
 * UGC-008 配置到业务消费者闭环集成测试（docs/13；03 §5.3；T31）：
 * REVIEW_ROUTING ACTIVE 修订解析值驱动机审路由——①置信下限 9950 使 0.99
 * 报告转人工（HUMAN_LOW_CONFIDENCE，案件停留 QUEUED 不发布）；②抽检比例
 * 10000 使全部自动决定转人工（灰度/全量槽语义由 GrayRolloutPolicy 单测覆盖）。
 * 旧基线激活拒绝已在 T30/T31 覆盖；本类不用 NeverSampledGate（抽检闸是被测对象）。
 */
@SpringBootTest(properties = "lumin.review.calibration-valid=true")
@AutoConfigureMockMvc
class UGC008ConfigWiringIntegrationTest extends MySqlIntegrationBase {

    private static final String OPERATOR = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.OPERATOR;
    private static final String ADMIN = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.ADMIN;

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
    }

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(org.springframework.test.web.servlet.ResultActions actions,
                                String name) throws java.io.UnsupportedEncodingException {
        return com.leyon.lumin.support.TestResponseSupport.field(actions, name);
    }

    /** 提案 + 独立审批 + 激活（返回修订号）。 */
    private long activateRouting(int minAutoConfidenceBp, int sampleRateBp) throws Exception {
        String payload = "{\"minAutoConfidenceBp\":" + minAutoConfidenceBp
                + ",\"sampleRateBp\":" + sampleRateBp + ",\"minorProtectionEnabled\":true}";
        String grantId = field(mockMvc.perform(post("/api/v1/config-revisions")
                        .header("X-Lumin-Demo-Subject", OPERATOR)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"configKey\":\"REVIEW_ROUTING\",\"payload\":\""
                                + payload.replace("\"", "\\\"") + "\","
                                + "\"reason\":\"路由参数调整\"}"))
                .andExpect(status().isOk()), "id");
        assertThat(mockMvc.perform(post("/api/v1/config-revisions/" + grantId + "/reviews")
                        .header("X-Lumin-Demo-Subject", ADMIN)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"decision\":\"APPROVE\",\"legalConfirmation\":true}"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("APPROVED");
        assertThat(mockMvc.perform(post("/api/v1/config-revisions/" + grantId + "/activations")
                        .header("X-Lumin-Demo-Subject", ADMIN)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("ACTIVE");
        return jdbc.queryForObject(
                "SELECT revision FROM config_revision WHERE id = ?", Long.class, grantId);
    }

    /** 提交作品并送 LOW+PASS 机审报告（置信 0.99）；返回 (workId, caseId)。 */
    private String[] submitAndReport(String title, String confidence) throws Exception {
        String workId = field(mockMvc.perform(post("/api/v1/works")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String draftId = field(mockMvc.perform(post("/api/v1/drafts")
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
                                + "\"suggestedDecision\":\"PASS\",\"grade\":\"G\",\"confidence\":"
                                + confidence + ",\"evidenceRefs\":[\"ev:1\"],\"inputTokens\":100,"
                                + "\"outputTokens\":10,\"durationMs\":1000}"))
                .andExpect(status().isOk());
        dispatcher.deliverPending();
        return new String[]{workId, caseId};
    }

    private String workState(String workId) {
        return jdbc.queryForObject(
                "SELECT business_state FROM work WHERE id = ?", String.class, workId);
    }

    private String caseState(String caseId) {
        return jdbc.queryForObject(
                "SELECT state FROM review_case WHERE id = ?", String.class, caseId);
    }

    @Test
    void raisedThresholdRoutesLowConfidenceToHuman() throws Exception {
        // 基线激活（9800/500，供 base_revision 绑定）；提升置信下限至 9950
        activateRouting(9800, 500);
        activateRouting(9950, 500);
        // 0.99 (9900bp) < 9950 → HUMAN_LOW_CONFIDENCE（确定性：不经过抽检闸）
        String[] raised = submitAndReport("阈值提升", "0.99");
        assertThat(caseState(raised[1])).isEqualTo("QUEUED");
        assertThat(workState(raised[0])).isEqualTo("DRAFT");
        // 人工路径：尚无系统裁决（HUMAN_LOW_CONFIDENCE），决策行 0
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM review_decision WHERE case_id = ?",
                Integer.class, raised[1])).isZero();
    }

    @Test
    void sampleRateFromConfigForcesHumanReview() throws Exception {
        // 抽检比例 10000bp（100%）：自动决定必转人工（配置驱动抽检闸）
        activateRouting(9800, 10000);
        String[] reported = submitAndReport("全量抽检", "0.9999");
        // 0.9999 ≥ 9800 命中自动路径 → 抽检闸 100% 命中 → 案件人工队列、作品未发布
        assertThat(caseState(reported[1])).isEqualTo("QUEUED");
        assertThat(workState(reported[0])).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM publication_pointer WHERE target_id = ?",
                Integer.class, reported[0])).isZero();
    }
}
