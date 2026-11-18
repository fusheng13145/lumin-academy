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
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T30–T32 配置修订链集成测试（03 §2.4 config_revision / 05 §3.4 §4.2）：
 * T30 提案→自批拒绝→独立 ADMIN 审批（法务确认必填）→提前激活 409→到时激活；
 * T31 降低置信度/抽检底线、关闭未成年保护、开启成年内容、白名单外字段 → 全拒绝
 * （提案不落行）；旧 If-Match 审批 412；批后 payload 冻结（无更新端点）；
 * T32 回滚走新提案（rollback_of 留痕）重复审批，不改写历史，回读当前策略一致。
 */
@SpringBootTest(properties = "lumin.review.calibration-valid=true")
@AutoConfigureMockMvc
class T30ConfigChainIntegrationTest extends MySqlIntegrationBase {

    private static final String OPERATOR = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.OPERATOR;
    private static final String ADMIN = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.ADMIN;
    private static final String ADMIN_SUBJECT =
            com.leyon.lumin.foundation.web.DemoAuthenticationFilter.ADMIN_SUBJECT;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seedFixtures() {
        MySqlFixtures.insertConfigStubs(jdbc);
    }

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(org.springframework.test.web.servlet.ResultActions actions,
                                String name) throws java.io.UnsupportedEncodingException {
        return com.leyon.lumin.support.TestResponseSupport.field(actions, name);
    }

    /** 提案（断言 200 并返回 id）。 */
    private String propose(String subject, String payload, String reason) throws Exception {
        return field(mockMvc.perform(post("/api/v1/config-revisions")
                        .header("X-Lumin-Demo-Subject", subject)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content(proposeBody(payload, reason, null)))
                .andExpect(status().isOk()), "id");
    }

    /** 提案（带生效时间；断言 200 并返回 id）。 */
    private String propose(String subject, String payload, String reason, String effectiveAt)
            throws Exception {
        return field(mockMvc.perform(post("/api/v1/config-revisions")
                        .header("X-Lumin-Demo-Subject", subject)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content(proposeBody(payload, reason, effectiveAt)))
                .andExpect(status().isOk()), "id");
    }

    /** 提案原始调用（返回 body，供拒绝断言）。 */
    private String proposeRaw(String subject, String configKey, String payload,
                              String reason, String effectiveAt, Integer rollout,
                              String rollbackOf) throws Exception {
        StringBuilder body = new StringBuilder("{\"configKey\":\"").append(configKey)
                .append("\",\"reason\":\"").append(reason).append("\"");
        if (payload != null) {
            body.append(",\"payload\":\"").append(payload.replace("\"", "\\\"")).append("\"");
        }
        if (effectiveAt != null) {
            body.append(",\"effectiveAt\":\"").append(effectiveAt).append("\"");
        }
        if (rollout != null) {
            body.append(",\"rolloutBasisPoints\":").append(rollout);
        }
        if (rollbackOf != null) {
            body.append(",\"rollbackOf\":\"").append(rollbackOf).append("\"");
        }
        body.append("}");
        return mockMvc.perform(post("/api/v1/config-revisions")
                        .header("X-Lumin-Demo-Subject", subject)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content(body.toString()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static String proposeBody(String payload, String reason, String effectiveAt) {
        StringBuilder body = new StringBuilder("{\"configKey\":\"REVIEW_ROUTING\",\"reason\":\"")
                .append(reason).append("\"");
        if (payload != null) {
            body.append(",\"payload\":\"").append(payload.replace("\"", "\\\"")).append("\"");
        }
        if (effectiveAt != null) {
            body.append(",\"effectiveAt\":\"").append(effectiveAt).append("\"");
        }
        return body.append("}").toString();
    }

    private String reviewRaw(String subject, String id, String ifMatch, String decision,
                             boolean legal) throws Exception {
        return mockMvc.perform(post("/api/v1/config-revisions/" + id + "/reviews")
                        .header("X-Lumin-Demo-Subject", subject)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", ifMatch)
                        .contentType("application/json")
                        .content("{\"decision\":\"" + decision + "\",\"reason\":\"审查\","
                                + "\"legalConfirmation\":" + legal + "}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String approve(String subject, String id, String ifMatch, boolean legal)
            throws Exception {
        String body = reviewRaw(subject, id, ifMatch, "APPROVE", legal);
        assertThat(body).contains("\"state\":\"APPROVED\"");
        return body;
    }

    private String activate(String subject, String id) throws Exception {
        return mockMvc.perform(post("/api/v1/config-revisions/" + id + "/activations")
                        .header("X-Lumin-Demo-Subject", subject)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private int revisionCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM config_revision WHERE config_key = 'REVIEW_ROUTING'",
                Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    void proposeApproveActivateChainHonorsSelfReviewAndEffectiveTime() throws Exception {
        // T30：OPERATOR 提案（生效时间 2 秒后）→ OPERATOR 自批拒绝 → 非 ADMIN 403
        String dueAt = Instant.now().plusSeconds(2).toString();
        String id = propose(OPERATOR,
                "{\"minAutoConfidenceBp\":9900,\"sampleRateBp\":500,\"minorProtectionEnabled\":true}",
                "审核路由基线", dueAt);
        assertThat(reviewRaw(OPERATOR, id, "\"1\"", "APPROVE", true)).contains("A2001");
        mockMvc.perform(post("/api/v1/config-revisions/" + id + "/reviews")
                        .header("X-Lumin-Demo-Subject",
                                com.leyon.lumin.foundation.web.DemoAuthenticationFilter.REVIEWER_L1A)
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json")
                        .content("{\"decision\":\"APPROVE\",\"legalConfirmation\":true}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A2001"));
        // 缺法务确认 → 400（审核规则批准须完成法务检查，05「必要合规证明」）
        assertThat(reviewRaw(ADMIN, id, "\"1\"", "APPROVE", false)).contains("P1001");
        approve(ADMIN, id, "\"1\"", true);
        // 提前激活 → 409（ACTIVE 只在到时出现）
        assertThat(activate(ADMIN, id)).contains("B2001");
        Thread.sleep(2200);
        assertThat(activate(ADMIN, id)).contains("\"state\":\"ACTIVE\"");
        mockMvc.perform(get("/api/v1/config-revisions/" + id)
                        .header("X-Lumin-Demo-Subject", ADMIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ACTIVE"))
                .andExpect(jsonPath("$.data.approvedBy").value(ADMIN_SUBJECT));
        // 读者替身无审查可见权 → 403
        mockMvc.perform(get("/api/v1/config-revisions")
                        .header("X-Lumin-Demo-Subject",
                                com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C3))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A2001"));
    }

    @Test
    void weakeningProposalsAndStaleLockAreRejected() throws Exception {
        // T31：先立基线（置信 9900 / 抽检 500）
        String baseline = propose(OPERATOR,
                "{\"minAutoConfidenceBp\":9900,\"sampleRateBp\":500,\"minorProtectionEnabled\":true}",
                "基线");
        approve(ADMIN, baseline, "\"1\"", true);
        assertThat(activate(ADMIN, baseline)).contains("ACTIVE");
        int rowsAfterBaseline = revisionCount();
        // 降低置信度 → 拒绝且不落行
        assertThat(proposeRaw(OPERATOR, "REVIEW_ROUTING", "{\"minAutoConfidenceBp\":9000}",
                "降置信", null, null, null)).contains("P1001");
        // 降低抽检底线 → 拒绝
        assertThat(proposeRaw(OPERATOR, "REVIEW_ROUTING", "{\"sampleRateBp\":100}",
                "降抽检", null, null, null)).contains("P1001");
        // 关闭未成年保护 → 拒绝
        assertThat(proposeRaw(OPERATOR, "REVIEW_ROUTING",
                "{\"minorProtectionEnabled\":false}", "关保护", null, null, null))
                .contains("P1001");
        // 开启成年内容（ACCESS_POLICY）→ 拒绝（P0：A 级默认关闭）
        assertThat(proposeRaw(OPERATOR, "ACCESS_POLICY", "{\"adultContentEnabled\":true}",
                "开成年", null, null, null)).contains("P1001");
        // 白名单外字段 → 拒绝（类型化提案）
        assertThat(proposeRaw(OPERATOR, "REVIEW_ROUTING", "{\"backdoor\":true}",
                "注入", null, null, null)).contains("P1001");
        assertThat(revisionCount()).isEqualTo(rowsAfterBaseline);
        // 旧 If-Match 审批 → 412/B2002（审批后乐观锁前移，批后不可重复审批）
        String id = propose(OPERATOR, "{\"minAutoConfidenceBp\":9950}", "升级置信");
        approve(ADMIN, id, "\"1\"", true);
        assertThat(reviewRaw(ADMIN, id, "\"1\"", "APPROVE", true)).contains("B2002");
        // 批后 payload 冻结：无更新端点——PUT 被拒（405 方法不支持 / 兜底 503 均非成功）
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/v1/config-revisions/" + id)
                        .header("X-Lumin-Demo-Subject", OPERATOR)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .isIn(405, 503));
    }

    @Test
    void rollbackCreatesNewRevisionThroughFullApproval() throws Exception {
        // T32：rev1（基线 9900）→ rev2（9950）激活 → 回滚 rev1 创建新修订重复审批
        String rev1 = propose(OPERATOR,
                "{\"minAutoConfidenceBp\":9900,\"sampleRateBp\":500}", "基线");
        approve(ADMIN, rev1, "\"1\"", true);
        assertThat(activate(ADMIN, rev1)).contains("ACTIVE");
        Long revision1 = jdbc.queryForObject(
                "SELECT revision FROM config_revision WHERE id = ?", Long.class, rev1);
        String rev2 = propose(OPERATOR, "{\"minAutoConfidenceBp\":9950}", "升级");
        approve(ADMIN, rev2, "\"1\"", true);
        assertThat(activate(ADMIN, rev2)).contains("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT state FROM config_revision WHERE id = ?", String.class, rev1))
                .isEqualTo("SUPERSEDED");
        // 回滚 = 新提案（rollback_of 指向 rev1，payload 取其冻结副本）→ 审批 → 激活
        String rollbackId = field(mockMvc.perform(post("/api/v1/config-revisions")
                        .header("X-Lumin-Demo-Subject", OPERATOR)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"configKey\":\"REVIEW_ROUTING\",\"reason\":\"回滚到基线\","
                                + "\"rollbackOf\":\"" + rev1 + "\"}"))
                .andExpect(status().isOk()), "id");
        String rollbackPayload = jdbc.queryForObject(
                "SELECT payload FROM config_revision WHERE id = ?", String.class, rollbackId);
        assertThat(rollbackPayload).contains("9900");
        approve(ADMIN, rollbackId, "\"1\"", true);
        assertThat(activate(ADMIN, rollbackId)).contains("ACTIVE");
        // rev2 → SUPERSEDED；当前全量基线 = 回滚修订 payload；revision 单调 + rollback_of 留痕
        assertThat(jdbc.queryForObject(
                "SELECT state FROM config_revision WHERE id = ?", String.class, rev2))
                .isEqualTo("SUPERSEDED");
        String currentPayload = jdbc.queryForObject(
                "SELECT payload FROM config_revision WHERE config_key = 'REVIEW_ROUTING' "
                        + "AND state = 'ACTIVE' AND rollout_basis_points = 10000", String.class);
        assertThat(currentPayload).isEqualTo(rollbackPayload);
        assertThat(jdbc.queryForObject(
                "SELECT revision FROM config_revision WHERE id = ?", Long.class, rollbackId))
                .isGreaterThan(revision1);
        assertThat(jdbc.queryForObject(
                "SELECT rollback_of FROM config_revision WHERE id = ?", String.class, rollbackId))
                .isEqualTo(rev1);
    }
}
