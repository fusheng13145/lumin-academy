package com.leyon.lumin.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UGC-048 口令找回 / 重置（V24；2026-11-20 审查 04 项收尾）。
 *
 * <p>覆盖两条通道与它们的反例：
 * <ol>
 *   <li><b>EMAIL</b>：注册带邮箱 → 请求重置 → 记录型网关拿到码 → 弱口令拒绝 →
 *       合规新口令生效（旧会话立即失效、旧口令登不上、旧码不可复用）；</li>
 *   <li>不存在/无邮箱账号请求重置 → 响应与成功路径一致（不可枚举）；</li>
 *   <li><b>CUSTOMER_SERVICE</b>：READER 无权列/批工单（403），OPERATOR 批准后拿到
 *       一次性码，确认后改密成功；驳回分支保持口令不变；</li>
 *   <li>已启用二次验证的账号重置口令后**仍要求 MFA**（重置不得成为绕过第二因子的通道）。</li>
 * </ol>
 * 邮件通道用 {@link RecordingGateway}（仅测试上下文注册，生产为"明确失败"兜底）。
 */
@SpringBootTest(properties = {"lumin.review.calibration-valid=true"})
@AutoConfigureMockMvc
class UGC048PasswordResetIntegrationTest extends MySqlIntegrationBase {

    private static final String STRONG = "Lumin#2026Safe";
    private static final String NEW_STRONG = "Shuyuan#2026Reset";
    private static final String EMAIL = "Reset2026@Example.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private RecordingGateway mailGateway;

    /** 测试专用邮件网关：把明文码留在内存（不落库、不进日志），供用例取用。 */
    @TestConfiguration
    static class RecordingMailConfig {
        @Bean
        @Primary
        RecordingGateway recordingGateway() {
            return new RecordingGateway();
        }
    }

    /** 记录型网关（只在测试上下文存在）。 */
    static class RecordingGateway implements
            com.leyon.lumin.identity.service.PasswordResetMailGateway {
        private final List<String> codes = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void send(String recipient, String code, LocalDateTime expiresAt) {
            codes.add(code);
        }

        String lastCode() {
            return codes.isEmpty() ? null : codes.get(codes.size() - 1);
        }

        void clear() {
            codes.clear();
        }
    }

    @org.junit.jupiter.api.BeforeEach
    void resetRecordingGateway() {
        mailGateway.clear();
    }

    // ---------- HTTP 辅助 ----------

    private JsonNode register(String loginId, String email) throws Exception {
        String body = "{\"loginId\":\"" + loginId + "\",\"password\":\"" + STRONG
                + "\",\"nickname\":\"重置读者\",\"birthDate\":\"2000-01-01\""
                + (email == null ? "" : ",\"email\":\"" + email + "\"") + "}";
        MvcResult result = mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType("application/json").content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(200);
        return data(result);
    }

    private MvcResult requestReset(String loginId, String channel) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-resets")
                        .contentType("application/json")
                        .content("{\"loginId\":\"" + loginId + "\",\"channel\":\"" + channel
                                + "\"}"))
                .andReturn();
    }

    private MvcResult confirmReset(String loginId, String code, String newPassword)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-reset-confirmations")
                        .contentType("application/json")
                        .content("{\"loginId\":\"" + loginId + "\",\"code\":\"" + code
                                + "\",\"newPassword\":\"" + newPassword + "\"}"))
                .andReturn();
    }

    private MvcResult login(String loginId, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-sessions")
                        .contentType("application/json")
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + password
                                + "\"}"))
                .andReturn();
    }

    private String stubToken(String stub) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/sessions")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content("{\"stub\":\"" + stub + "\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return data(result).get("token").asText();
    }

    private static JsonNode data(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data");
    }

    // ---------- 用例 ----------

    @Test
    void emailChannelResetChangesPasswordAndInvalidatesOldSession() throws Exception {
        JsonNode registered = register("reset2026", EMAIL);
        String oldToken = registered.get("token").asText();
        String subject = registered.get("subject").asText();
        // 邮箱统一小写入库
        assertThat(jdbc.queryForObject(
                "SELECT email FROM password_credential WHERE login_id = 'reset2026'",
                String.class)).isEqualTo(EMAIL.toLowerCase());

        assertThat(requestReset("reset2026", "EMAIL").getResponse().getStatus()).isEqualTo(200);
        String code = mailGateway.lastCode();
        assertThat(code).isNotBlank().hasSize(11); // 5-5 分段（含连字符）
        // 工单只存摘要
        String hash = jdbc.queryForObject(
                "SELECT code_hash FROM password_reset_ticket WHERE login_id = 'reset2026'",
                String.class);
        assertThat(hash).hasSize(64).doesNotContain(code.replace("-", ""));
        assertThat(jdbc.queryForObject(
                "SELECT state FROM password_reset_ticket WHERE login_id = 'reset2026'",
                String.class)).isEqualTo("PENDING");

        // 弱口令拒绝（策略同注册/改密）
        assertThat(confirmReset("reset2026", code, "123456").getResponse().getStatus())
                .isEqualTo(400);
        // 合规新口令 → 成功
        assertThat(confirmReset("reset2026", code, NEW_STRONG).getResponse().getStatus())
                .isEqualTo(200);
        // 旧会话立即失效
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());
        // 旧口令登不上，新口令可登录
        assertThat(login("reset2026", STRONG).getResponse().getStatus()).isEqualTo(401);
        MvcResult loginResult = login("reset2026", NEW_STRONG);
        assertThat(loginResult.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(loginResult).get("token").asText()).isNotBlank();
        // 码单次：复用直接 401
        assertThat(confirmReset("reset2026", code, STRONG).getResponse().getStatus())
                .isEqualTo(401);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM password_reset_ticket WHERE login_id = 'reset2026'",
                String.class)).isEqualTo("CONSUMED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_session WHERE subject = ? AND revoked_at IS NULL",
                Integer.class, subject)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void requestDoesNotRevealWhetherAccountExists() throws Exception {
        register("exists2026", EMAIL);
        MvcResult existing = requestReset("exists2026", "EMAIL");
        MvcResult missing = requestReset("nosuch2026", "EMAIL");
        assertThat(existing.getResponse().getStatus()).as("已存在账号")
                .isEqualTo(200);
        assertThat(missing.getResponse().getStatus()).as("不存在的账号").isEqualTo(200);
        assertThat(existing.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"accepted\":true");
        assertThat(missing.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("\"accepted\":true");
        // 不存在账号不建工单、不发信
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM password_reset_ticket WHERE login_id = 'nosuch2026'",
                Integer.class)).isZero();
    }

    @Test
    void accountWithoutEmailFallsBackToManualTicket() throws Exception {
        register("noemail2026", null);
        assertThat(requestReset("noemail2026", "EMAIL").getResponse().getStatus()).isEqualTo(200);
        // 没有邮箱 → 工单落 DELIVERY_FAILED（运营可见），而不是假装发信成功
        assertThat(jdbc.queryForObject(
                "SELECT state FROM password_reset_ticket WHERE login_id = 'noemail2026'",
                String.class)).isEqualTo("DELIVERY_FAILED");
        assertThat(mailGateway.lastCode()).as("不得凭空生成并投递码").isNull();
    }

    @Test
    void customerServiceChannelRequiresOperatorAndIssuesSingleUseCode() throws Exception {
        JsonNode registered = register("csreset2026", null);
        String subject = registered.get("subject").asText();
        assertThat(requestReset("csreset2026", "CUSTOMER_SERVICE").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM password_reset_ticket WHERE login_id = 'csreset2026'",
                String.class)).isEqualTo("AWAITING_OPERATOR");

        // READER 无权列工单
        mockMvc.perform(get("/api/v1/password-reset-tickets")
                        .header("Authorization", "Bearer " + registered.get("token").asText()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A2001"));

        // OPERATOR 可见并批准，拿到一次性码
        String operator = stubToken("OPERATOR");
        MvcResult list = mockMvc.perform(get("/api/v1/password-reset-tickets")
                        .header("Authorization", "Bearer " + operator))
                .andExpect(status().isOk())
                .andReturn();
        String ticketId = data(list).get(0).get("id").asText();
        MvcResult issued = mockMvc.perform(post("/api/v1/password-reset-tickets/" + ticketId
                                + "/decisions")
                        .header("Authorization", "Bearer " + operator)
                        .contentType("application/json")
                        .content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String code = data(issued).get("resetCode").asText();
        assertThat(code).isNotBlank();
        // 批准后工单转为待确认
        assertThat(jdbc.queryForObject(
                "SELECT state FROM password_reset_ticket WHERE id = ?", String.class, ticketId))
                .isEqualTo("PENDING");

        assertThat(confirmReset("csreset2026", code, NEW_STRONG).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(login("csreset2026", NEW_STRONG).getResponse().getStatus()).isEqualTo(200);
        assertThat(login("csreset2026", STRONG).getResponse().getStatus()).isEqualTo(401);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_session WHERE subject = ? AND revoked_at IS NULL",
                Integer.class, subject)).isEqualTo(1); // 仅新登录那一枚
    }

    @Test
    void rejectedCustomerServiceTicketLeavesPasswordUntouched() throws Exception {
        register("csreject2026", null);
        assertThat(requestReset("csreject2026", "CUSTOMER_SERVICE").getResponse().getStatus())
                .isEqualTo(200);
        String operator = stubToken("ADMIN");
        String ticketId = data(mockMvc.perform(get("/api/v1/password-reset-tickets")
                        .header("Authorization", "Bearer " + operator)).andReturn())
                .get(0).get("id").asText();
        MvcResult rejected = mockMvc.perform(post("/api/v1/password-reset-tickets/" + ticketId
                                + "/decisions")
                        .header("Authorization", "Bearer " + operator)
                        .contentType("application/json")
                        .content("{\"decision\":\"REJECTED\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(data(rejected).get("resetCode").asText()).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM password_reset_ticket WHERE id = ?", String.class, ticketId))
                .isEqualTo("REJECTED");
        // 口令未被改动：原口令仍可登录
        assertThat(login("csreject2026", STRONG).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void resetDoesNotBypassSecondFactor() throws Exception {
        JsonNode registered = register("mfaReset2026", EMAIL);
        String token = registered.get("token").asText();
        String secret = data(mockMvc.perform(post("/api/v1/auth/mfa/enrollments")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"accountLabel\":\"reset-test\"}")).andReturn())
                .get("secret").asText();
        String code = com.leyon.lumin.identity.domain.Totp.code(
                com.leyon.lumin.identity.domain.Base32.decode(secret),
                com.leyon.lumin.identity.domain.Totp.stepAt(
                        java.time.Instant.now().getEpochSecond()));
        assertThat(mockMvc.perform(post("/api/v1/auth/mfa/confirmations")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"code\":\"" + code + "\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        // 重置口令
        assertThat(requestReset("mfaReset2026", "EMAIL").getResponse().getStatus()).isEqualTo(200);
        assertThat(confirmReset("mfaReset2026", mailGateway.lastCode(), NEW_STRONG)
                .getResponse().getStatus()).isEqualTo(200);
        // 新口令登录仍必须过二次验证（不发会话令牌）
        JsonNode loginData = data(login("mfaReset2026", NEW_STRONG));
        assertThat(loginData.get("mfaRequired").asBoolean()).isTrue();
        assertThat(loginData.has("token")).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM mfa_totp_credential WHERE subject = ?", String.class,
                registered.get("subject").asText())).isEqualTo("ACTIVE");
    }
}
