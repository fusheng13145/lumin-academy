package com.leyon.lumin.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.lumin.identity.domain.Base32;
import com.leyon.lumin.identity.domain.Totp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
 * UGC-049 二次验证运维解绑（V23）。
 *
 * <p>覆盖：
 * <ol>
 *   <li>已启用 MFA 的用户可提工单（重复提 409），READER 无权列/批；</li>
 *   <li>OPERATOR 批准 → 种子 {@code ACTIVE→REVOKED}、恢复码清空、全部会话撤销，
 *       重新登录不再要求二次验证；</li>
 *   <li>驳回 → 种子保持 ACTIVE、登录仍要求挑战（拒绝不产生副作用）；</li>
 *   <li>未启用 MFA 的账号提工单 → 409（没有第二因子可解绑）。</li>
 * </ol>
 */
@SpringBootTest(properties = {"lumin.review.calibration-valid=true"})
@AutoConfigureMockMvc
class UGC049MfaUnbindIntegrationTest extends MySqlIntegrationBase {

    private static final String STRONG = "Lumin#2026Safe";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private static JsonNode data(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data");
    }

    private static String currentCode(String secretBase32) {
        return Totp.code(Base32.decode(secretBase32),
                Totp.stepAt(Instant.now().getEpochSecond()));
    }

    private MvcResult register(String loginId) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType("application/json")
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + STRONG
                                + "\",\"nickname\":\"解绑读者\",\"birthDate\":\"2000-01-01\"}"))
                .andReturn();
    }

    /** 注册 → 登记 → 确认的结果（subject 取自注册响应；token 为确认后换发的正式令牌）。 */
    private record Enrolled(String subject, String token) {
    }

    /** 注册 → 登记 → 确认，返回正式令牌与主体。 */
    private Enrolled enrollMfa(String loginId) throws Exception {
        JsonNode registered = data(register(loginId));
        String token = registered.get("token").asText();
        String secret = data(mockMvc.perform(post("/api/v1/auth/mfa/enrollments")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json").content("{}")).andReturn())
                .get("secret").asText();
        MvcResult confirmed = mockMvc.perform(post("/api/v1/auth/mfa/confirmations")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"code\":\"" + currentCode(secret) + "\"}"))
                .andReturn();
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        return new Enrolled(registered.get("subject").asText(),
                data(confirmed).get("token").asText());
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

    private MvcResult requestUnbind(String token, String reason) throws Exception {
        return mockMvc.perform(post("/api/v1/mfa-unbind-requests")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"reason\":\"" + reason + "\"}"))
                .andReturn();
    }

    @Test
    void approvedUnbindRevokesCredentialRecoveryCodesAndSessions() throws Exception {
        Enrolled enrolled = enrollMfa("unbind2026");
        String subject = enrolled.subject();
        String token = enrolled.token();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mfa_recovery_code WHERE subject = ?", Integer.class,
                subject)).isEqualTo(8);

        JsonNode ticket = data(requestUnbind(token, "手机丢失，无法出示验证码"));
        String ticketId = ticket.get("id").asText();
        assertThat(ticket.get("state").asText()).isEqualTo("PENDING");
        // 同一主体不能重复提
        assertThat(requestUnbind(token, "再次提交").getResponse().getStatus()).isEqualTo(409);

        // 普通读者无权查看工单
        mockMvc.perform(get("/api/v1/mfa-unbind-requests")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A2001"));

        // OPERATOR 批准
        String operator = stubToken("OPERATOR");
        MvcResult list = mockMvc.perform(get("/api/v1/mfa-unbind-requests")
                        .header("Authorization", "Bearer " + operator))
                .andExpect(status().isOk()).andReturn();
        assertThat(data(list).get(0).get("id").asText()).isEqualTo(ticketId);
        MvcResult decided = mockMvc.perform(post("/api/v1/mfa-unbind-requests/" + ticketId
                                + "/decisions")
                        .header("Authorization", "Bearer " + operator)
                        .contentType("application/json")
                        .content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode decision = data(decided);
        assertThat(decision.get("state").asText()).isEqualTo("APPROVED");
        assertThat(decision.get("revokedSessions").asInt()).isGreaterThan(0);

        // 库里：种子作废、恢复码清空、会话全撤
        assertThat(jdbc.queryForObject(
                "SELECT status FROM mfa_totp_credential WHERE subject = ?", String.class, subject))
                .isEqualTo("REVOKED");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mfa_recovery_code WHERE subject = ?", Integer.class,
                subject)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_session WHERE subject = ? AND revoked_at IS NULL",
                Integer.class, subject)).isZero();
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        // 重新登录：不再要求挑战（第二因子已解除），可再次登记
        JsonNode loginData = data(mockMvc.perform(post("/api/v1/auth/password-sessions")
                        .contentType("application/json")
                        .content("{\"loginId\":\"unbind2026\",\"password\":\"" + STRONG + "\"}"))
                .andReturn());
        assertThat(loginData.get("mfaRequired").asBoolean()).isFalse();
        assertThat(loginData.get("token").asText()).isNotBlank();
        // 审计：批准动作留痕（resourceType=mfa_unbind_request，快照只含 mfaState）
        String auditJson = jdbc.queryForObject(
                "SELECT before_redacted FROM audit_record WHERE action = 'mfa.unbind.approved'",
                String.class);
        assertThat(auditJson).contains("mfaState").contains("ACTIVE");
    }

    @Test
    void rejectedUnbindKeepsSecondFactorActive() throws Exception {
        Enrolled enrolled = enrollMfa("unbindreject2026");
        String subject = enrolled.subject();
        String token = enrolled.token();
        String ticketId = data(requestUnbind(token, "换手机")).get("id").asText();

        String admin = stubToken("ADMIN");
        MvcResult decided = mockMvc.perform(post("/api/v1/mfa-unbind-requests/" + ticketId
                                + "/decisions")
                        .header("Authorization", "Bearer " + admin)
                        .contentType("application/json")
                        .content("{\"decision\":\"REJECTED\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(data(decided).get("state").asText()).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM mfa_totp_credential WHERE subject = ?", String.class, subject))
                .isEqualTo("ACTIVE");
        // 仍要求二次验证（且原令牌继续可用——驳回不撤会话）
        JsonNode loginData = data(mockMvc.perform(post("/api/v1/auth/password-sessions")
                        .contentType("application/json")
                        .content("{\"loginId\":\"unbindreject2026\",\"password\":\"" + STRONG + "\"}"))
                .andReturn());
        assertThat(loginData.get("mfaRequired").asBoolean()).isTrue();
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void requestRequiresAnActiveSecondFactor() throws Exception {
        JsonNode registered = data(register("unbindnoMfa2026"));
        MvcResult result = requestUnbind(registered.get("token").asText(), "想解绑");
        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mfa_unbind_request WHERE subject = ?", Integer.class,
                registered.get("subject").asText())).isZero();
    }
}
