package com.leyon.lumin.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UGC-044 口令凭据集成测试（2026-11-20 安全审查 05 项；真实 MySQL）。
 *
 * <p>覆盖：口令策略拒绝（长度/字符类/含登录名）→ 注册落 BCrypt 摘要 → 重复登录名 409 →
 * 登录 5 次失败锁定 15 分钟（库内权威）→ 改密后旧令牌立即失效、新令牌可用、
 * 旧口令不能再登录。断言同时含接口响应与数据库状态（双重验收）。
 */
@SpringBootTest(properties = {"lumin.review.calibration-valid=true"})
@AutoConfigureMockMvc
class UGC044PasswordAuthIntegrationTest extends MySqlIntegrationBase {

    private static final String STRONG = "Lumin#2026Safe";
    private static final String NEW_STRONG = "Shuyuan#2026Key";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanCredentials() {
        jdbc.update("DELETE FROM password_credential");
    }

    private MvcResult register(String loginId, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType("application/json")
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + password
                                + "\",\"nickname\":\"测试读者\",\"birthDate\":\"2000-01-01\"}"))
                .andReturn();
    }

    private MvcResult login(String loginId, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-sessions")
                        .contentType("application/json")
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + password
                                + "\"}"))
                .andReturn();
    }

    @Test
    void passwordPolicyRejectsWeakAndLoginIdContainingPasswords() throws Exception {
        // 过短
        assertThat(register("alice2026", "123456").getResponse().getStatus()).isEqualTo(400);
        // 字符类不足（小写+数字）
        assertThat(register("alice2026", "password123").getResponse().getContentAsString())
                .contains("P1001");
        // 含登录名
        assertThat(register("lumin2026", "Lumin2026Ab#").getResponse().getContentAsString())
                .contains("P1001");
        // 合规口令 → 注册成功并签发会话
        MvcResult ok = register("alice2026", STRONG);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("token");
        // 重复登录名 → 409
        assertThat(register("alice2026", STRONG).getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void passwordIsStoredOnlyAsBcryptDigest() throws Exception {
        register("bob2026", STRONG);
        String hash = jdbc.queryForObject(
                "SELECT password_hash FROM password_credential WHERE login_id = 'bob2026'",
                String.class);
        assertThat(hash).startsWith("$2a$").hasSizeGreaterThan(50);
        assertThat(hash).doesNotContain(STRONG);
        // 明文不得出现在任何列
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM password_credential WHERE password_hash LIKE ?",
                Integer.class, "%" + STRONG + "%")).isZero();
    }

    @Test
    void registrationIssuesUsableSession() throws Exception {
        MvcResult registered = register("carol2026", STRONG);
        String token = TestResponseSupport.field(registered, "token");
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNotEmpty());
    }

    @Test
    void fiveFailedLoginsLockAccountInDatabase() throws Exception {
        register("dave2026", STRONG);
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(login("dave2026", "Wrong#Pass2026").getResponse().getStatus())
                    .isEqualTo(401);
        }
        assertThat(jdbc.queryForObject(
                "SELECT locked_until FROM password_credential WHERE login_id = 'dave2026'",
                java.time.LocalDateTime.class)).isNotNull();
        // 锁定期间即使口令正确也拒绝
        assertThat(login("dave2026", STRONG).getResponse().getStatus()).isEqualTo(401);
        // 未知登录名与口令错误返回同一 401（不可用状态码枚举账号）
        assertThat(login("nobody2026", STRONG).getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void correctLoginIssuesSessionAndResetsCounter() throws Exception {
        register("erin2026", STRONG);
        assertThat(login("erin2026", "Wrong#Pass2026").getResponse().getStatus()).isEqualTo(401);
        MvcResult ok = login("erin2026", STRONG);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT failed_count FROM password_credential WHERE login_id = 'erin2026'",
                Integer.class)).isZero();
        String token = TestResponseSupport.field(ok, "token");
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void passwordChangeRevokesOldSessionsAndNewTokenWorks() throws Exception {
        String token = TestResponseSupport.field(register("frank2026", STRONG), "token");
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        MvcResult changed = mockMvc.perform(post("/api/v1/auth/password-changes")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"" + STRONG + "\",\"newPassword\":\""
                                + NEW_STRONG + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String newToken = TestResponseSupport.field(changed, "token");
        // 旧令牌立即失效（04 项要求）
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0001"));
        // 新令牌可用
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk());
        // 旧口令不能再登录，新口令可以
        assertThat(login("frank2026", STRONG).getResponse().getStatus()).isEqualTo(401);
        assertThat(login("frank2026", NEW_STRONG).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void passwordChangeRejectsBadCurrentPasswordAndReuse() throws Exception {
        String token = TestResponseSupport.field(register("grace2026", STRONG), "token");
        mockMvc.perform(post("/api/v1/auth/password-changes")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"Wrong#Pass2026\",\"newPassword\":\""
                                + NEW_STRONG + "\"}"))
                .andExpect(status().isUnauthorized());
        // 复用旧口令 → 400
        mockMvc.perform(post("/api/v1/auth/password-changes")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"" + STRONG + "\",\"newPassword\":\""
                                + STRONG + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("P1001"));
    }

    /** demo 模式下替身签发仍可用（开发/测试骨架不被误伤）。 */
    @Test
    void demoStubIssuanceStillWorksInDemoMode() throws Exception {
        mockMvc.perform(post("/api/v1/auth/sessions")
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType("application/json").content("{\"stub\":\"AUTHOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());
        // 有界集合外的替身仍 401
        mockMvc.perform(post("/api/v1/auth/sessions")
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType("application/json").content("{\"stub\":\"HACKER\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0001"));
    }
}
