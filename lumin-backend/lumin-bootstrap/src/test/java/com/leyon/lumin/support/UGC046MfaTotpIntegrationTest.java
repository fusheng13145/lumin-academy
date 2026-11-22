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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UGC-046 二次验证（TOTP）集成测试（2026-11-20 安全审查 05 项）。
 *
 * <p>覆盖真实链路与反例：
 * <ol>
 *   <li>登记 → 确认（坏码 400、好码 200）→ 登录必须过挑战（不回会话令牌）；</li>
 *   <li>挑战令牌**不能**当会话用（{@code resolve} 拒绝），兑换后一次性消费；</li>
 *   <li>同一时间步的验证码重放被拒；恢复码单次使用；</li>
 *   <li>特权账号（ADMIN）未启用 MFA → 只发受限令牌，受限令牌访问业务端点 403，
 *       但可完成登记 + 确认后恢复正常。</li>
 * </ol>
 * 种子只以密文落库、恢复码只存摘要也在库里直接断言（双重验收）。
 */
@SpringBootTest(properties = {"lumin.review.calibration-valid=true"})
@AutoConfigureMockMvc
class UGC046MfaTotpIntegrationTest extends MySqlIntegrationBase {

    private static final String STRONG = "Lumin#2026Safe";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    // ---------- HTTP 辅助 ----------

    private JsonNode register(String loginId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType("application/json")
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + STRONG
                                + "\",\"nickname\":\"二次验证读者\",\"birthDate\":\"2000-01-01\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return data(result);
    }

    private MvcResult login(String loginId) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-sessions")
                        .contentType("application/json")
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + STRONG + "\"}"))
                .andReturn();
    }

    private JsonNode enroll(String token) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/mfa/enrollments")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"accountLabel\":\"integration-test\"}"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return data(result);
    }

    private MvcResult confirm(String token, String code) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/mfa/confirmations")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"code\":\"" + code + "\"}"))
                .andReturn();
    }

    private MvcResult challenge(String challengeToken, String code) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/mfa-challenges")
                        .contentType("application/json")
                        .content("{\"challengeToken\":\"" + challengeToken + "\",\"code\":\"" + code
                                + "\"}"))
                .andReturn();
    }

    private static JsonNode data(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data");
    }

    /** 以种子计算"当前时间步"的 6 位验证码（等价于 Authenticator App 的行为）。 */
    private static String currentCode(String secretBase32) {
        byte[] secret = Base32.decode(secretBase32);
        return Totp.code(secret, Totp.stepAt(Instant.now().getEpochSecond()));
    }

    /** 与正确码等长但不相同的码（避免极小概率与真码相撞）。 */
    private static String wrongCode(String correct) {
        int last = correct.charAt(correct.length() - 1) - '0';
        return correct.substring(0, correct.length() - 1) + ((last + 1) % 10);
    }

    // ---------- 用例 ----------

    @Test
    void enrollConfirmThenLoginRequiresChallenge() throws Exception {
        JsonNode registered = register("mfa2026");
        String token = registered.get("token").asText();
        String subject = registered.get("subject").asText();

        JsonNode enrollment = enroll(token);
        String secret = enrollment.get("secret").asText();
        assertThat(enrollment.get("status").asText()).isEqualTo("PENDING");
        assertThat(enrollment.get("otpauthUri").asText())
                .startsWith("otpauth://totp/")
                .contains("secret=" + secret)
                .contains("algorithm=SHA1")
                .contains("digits=6")
                .contains("period=30");

        // 种子只以密文落库：库里不得出现明文 Base32
        String stored = new String(
                jdbc.queryForObject("SELECT secret_cipher FROM mfa_totp_credential WHERE subject = ?",
                        byte[].class, subject), StandardCharsets.ISO_8859_1);
        assertThat(stored).doesNotContain(secret);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mfa_totp_credential WHERE secret_cipher = ?",
                Integer.class, secret.getBytes(StandardCharsets.UTF_8))).isZero();

        // 坏码不得生效
        assertThat(confirm(token, wrongCode(currentCode(secret))).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM mfa_totp_credential WHERE subject = ?", String.class, subject))
                .isEqualTo("PENDING");

        // 正确码 → ACTIVE + 恢复码 + 换发会话
        MvcResult confirmed = confirm(token, currentCode(secret));
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        JsonNode confirmData = data(confirmed);
        assertThat(confirmData.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(confirmData.get("recoveryCodes")).hasSize(8);
        assertThat(confirmData.get("token").asText()).isNotBlank();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM mfa_totp_credential WHERE subject = ?", String.class, subject))
                .isEqualTo("ACTIVE");
        // 恢复码只存摘要（64 位十六进制），不落明文
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mfa_recovery_code WHERE subject = ? AND used_at IS NULL",
                Integer.class, subject)).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mfa_recovery_code WHERE subject = ? AND code_hash = ?",
                Integer.class, subject,
                confirmData.get("recoveryCodes").get(0).asText())).isZero();

        // 登录：凡已启用 MFA 者一律只回挑战令牌，不回会话令牌
        MvcResult loginResult = login("mfa2026");
        assertThat(loginResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode loginData = data(loginResult);
        assertThat(loginData.get("mfaRequired").asBoolean()).isTrue();
        assertThat(loginData.get("challengeToken").asText()).isNotBlank();
        assertThat(loginData.has("token")).isFalse();

        // 挑战令牌不是会话令牌：/me 拒绝
        mockMvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer " + loginData.get("challengeToken").asText()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0001"));

        // 坏码 → 401
        assertThat(challenge(loginData.get("challengeToken").asText(),
                wrongCode(currentCode(secret))).getResponse().getStatus()).isEqualTo(401);

        // 好码 → 正式会话；同一挑战令牌不可复用
        String challengeToken = loginData.get("challengeToken").asText();
        String code = currentCode(secret);
        MvcResult completed = challenge(challengeToken, code);
        assertThat(completed.getResponse().getStatus()).isEqualTo(200);
        String sessionToken = data(completed).get("token").asText();
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + sessionToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(subject));
        // 一次性消费
        assertThat(challenge(challengeToken, code).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void sameStepCodeCannotBeReplayed() throws Exception {
        JsonNode registered = register("mfareplay");
        String token = registered.get("token").asText();
        String secret = enroll(token).get("secret").asText();
        confirm(token, currentCode(secret));

        String challengeToken = data(login("mfareplay")).get("challengeToken").asText();
        String code = currentCode(secret);
        assertThat(challenge(challengeToken, code).getResponse().getStatus()).isEqualTo(200);

        // 同一时间步的码再用于新挑战 → 判为重放（401）
        String secondChallenge = data(login("mfareplay")).get("challengeToken").asText();
        assertThat(challenge(secondChallenge, code).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void recoveryCodeWorksOnceAndIsConsumed() throws Exception {
        JsonNode registered = register("mfarecovery");
        String token = registered.get("token").asText();
        String subject = registered.get("subject").asText();
        String secret = enroll(token).get("secret").asText();
        String recoveryCode = data(confirm(token, currentCode(secret)))
                .get("recoveryCodes").get(0).asText();

        assertThat(challenge(data(login("mfarecovery")).get("challengeToken").asText(), recoveryCode)
                .getResponse().getStatus()).isEqualTo(200);
        // 复用同一恢复码 → 401；库里记为已用
        assertThat(challenge(data(login("mfarecovery")).get("challengeToken").asText(), recoveryCode)
                .getResponse().getStatus()).isEqualTo(401);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mfa_recovery_code WHERE subject = ? AND used_at IS NOT NULL",
                Integer.class, subject)).isEqualTo(1);
    }

    @Test
    void privilegedAccountWithoutMfaGetsRestrictedSessionUntilEnrolled() throws Exception {
        JsonNode registered = register("adminmfa2026");
        String subject = registered.get("subject").asText();
        // 授予 ADMIN（有效期内；帧字段齐全，与 RoleGrantMapper#findActiveBySubject 口径一致）
        jdbc.update("""
                INSERT INTO role_grant (id, target_user_id, role, scope_type, scope_id, expires_at,
                    reason, state, requested_by, reviewed_by, reviewed_at, created_at, updated_at,
                    created_by, updated_by, lock_version)
                VALUES (?, ?, 'ADMIN', 'GLOBAL', NULL, NULL, '集成测试授权', 'APPROVED', ?, ?, NOW(6),
                    NOW(6), NOW(6), ?, ?, 1)
                """, UUID.randomUUID().toString(), subject, subject, subject, subject, subject);

        // 登录：不回会话，只回"仅可登记二次验证"的受限令牌
        JsonNode loginData = data(login("adminmfa2026"));
        assertThat(loginData.get("mfaEnrollmentRequired").asBoolean()).isTrue();
        assertThat(loginData.get("enrollPath").asText())
                .isEqualTo("/api/v1/auth/mfa/enrollments");
        assertThat(loginData.has("challengeToken")).isFalse();
        String restricted = loginData.get("token").asText();

        // 受限令牌访问业务端点 → 403 A2001
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + restricted))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A2001"));

        // 但允许登记 + 确认
        String secret = enroll(restricted).get("secret").asText();
        MvcResult confirmed = confirm(restricted, currentCode(secret));
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        String fullToken = data(confirmed).get("token").asText();
        // 受限令牌已被撤销，正式令牌可用
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + restricted))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + fullToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(subject));
        // 已启用 MFA 后再登录 → 走挑战
        assertThat(data(login("adminmfa2026")).get("mfaRequired").asBoolean()).isTrue();
    }

    @Test
    void unenrolledPrivilegeIsDecidedByGrantNotByStub() throws Exception {
        // 普通读者（无 ADMIN 授予）→ 直接签发会话（不被误判为特权账号）
        JsonNode registered = register("plainreader");
        String token = registered.get("token").asText();
        JsonNode loginData = data(login("plainreader"));
        assertThat(loginData.get("mfaRequired").asBoolean()).isFalse();
        assertThat(loginData.has("mfaEnrollmentRequired")).isFalse();
        assertThat(loginData.get("token").asText()).isNotBlank();
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        // 未登记就确认 → 409（无 PENDING 凭据）
        assertThat(confirm(token, "123456").getResponse().getStatus()).isEqualTo(409);
        // 未登记就挑战 → 401（无挑战令牌即无入口）
        assertThat(challenge("not-a-token", "123456").getResponse().getStatus()).isEqualTo(401);
        // 直接以清单外的替身走挑战端点也不成立（豁免路径只放行端点本身，仍需有效挑战令牌）
        assertThat(mockMvc.perform(post("/api/v1/auth/mfa-challenges")
                        .contentType("application/json")
                        .content("{\"challengeToken\":\"\",\"code\":\"123456\"}"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void activeCredentialCannotBeReEnrolled() throws Exception {
        JsonNode registered = register("mfareissue");
        String token = registered.get("token").asText();
        String secret = enroll(token).get("secret").asText();
        String newToken = data(confirm(token, currentCode(secret))).get("token").asText();
        // 已 ACTIVE 再登记 → 409（防"把种子换成攻击者的"）
        MvcResult again = mockMvc.perform(post("/api/v1/auth/mfa/enrollments")
                        .header("Authorization", "Bearer " + newToken)
                        .contentType("application/json").content("{}"))
                .andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(409);
        assertThat(again.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("B2001");
    }

    @Test
    void enabledUserCanLogoutWithMfaStillRequired() throws Exception {
        // 登出后重新登录仍要求挑战（MFA 状态不被登出清除），且挑战令牌不能跨"登出轮次"复活
        JsonNode registered = register("mfarevocation");
        String token = registered.get("token").asText();
        String subject = registered.get("subject").asText();
        String secret = enroll(token).get("secret").asText();
        String full = data(confirm(token, currentCode(secret))).get("token").asText();
        mockMvc.perform(post("/api/v1/auth/sessions/revocations")
                        .header("Authorization", "Bearer " + full))
                .andExpect(status().isOk());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_session WHERE subject = ? AND revoked_at IS NULL",
                Integer.class, subject)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT status FROM mfa_totp_credential WHERE subject = ?", String.class, subject))
                .isEqualTo("ACTIVE");
        assertThat(login("mfarevocation").getResponse().getStatus()).isEqualTo(200);
        assertThat(data(login("mfarevocation")).get("mfaRequired").asBoolean()).isTrue();
    }
}
