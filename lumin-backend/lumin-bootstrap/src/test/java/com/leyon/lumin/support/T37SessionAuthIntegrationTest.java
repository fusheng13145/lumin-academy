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
 * UGC-002 一阶段会话认证集成测试（docs/13；05 §2）：
 * 签发（有界替身）→ Bearer 使用 → 伪造/缺失 401/A0001；`lumin.auth.mode=session`
 * 下演示身份头被忽略（生产无法使用演示头的结构性闸门）；登出撤销幂等、撤销后
 * 令牌失效；过期令牌 401；未知替身 401；注销联动撤销全部会话（T36 钩子）。
 */
@SpringBootTest(properties = {"lumin.review.calibration-valid=true",
        "lumin.auth.mode=session"})
@AutoConfigureMockMvc
class T37SessionAuthIntegrationTest extends MySqlIntegrationBase {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void seedFixtures() {
        MySqlFixtures.insertStubAuthorProfile(jdbc);
    }

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    /**
     * 直接种入服务端会话行（2026-11-20 安全审查修复后，替身签发端点在生产/会话模式下
     * 已关闭为 403/A2001；本类只验证"令牌解析/撤销/过期/注销联动"语义，故用库内
     * 权威会话行代替签发，真实签发链路由口令登录测试覆盖）。
     */
    private String seedSession(String roleContext) throws Exception {
        String token = UUID.randomUUID() + "." + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO auth_session (id, token_hash, subject, stub_name, role_context,
                    issued_at, expires_at, created_at, updated_at, created_by, updated_by,
                    lock_version)
                VALUES (?, SHA2(?, 256), ?, 'SEEDED', ?, NOW(6),
                    NOW(6) + INTERVAL 12 HOUR, NOW(6), NOW(6), ?, ?, 1)
                """, UUID.randomUUID().toString(), token, MySqlFixtures.STUB_SUBJECT,
                roleContext, MySqlFixtures.STUB_SUBJECT, MySqlFixtures.STUB_SUBJECT);
        return token;
    }

    @Test
    void issuedSessionAuthorizesAndForgedOrMissingTokensAreRejected() throws Exception {
        String token = seedSession("AUTHOR");
        // Bearer 令牌访问受保护端点 → 200（服务端权威主体）
        mockMvc.perform(get("/api/v1/me/works").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        // 伪造令牌 → 401/A0001
        mockMvc.perform(get("/api/v1/me/works")
                        .header("Authorization", "Bearer " + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0001"));
        // 缺失 Authorization → 401（session 模式）
        mockMvc.perform(get("/api/v1/me/works"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0001"));
        // 演示身份头被忽略（生产无法使用演示头的结构性闸门）
        mockMvc.perform(get("/api/v1/me/works")
                        .header("X-Lumin-Demo-Subject", "AUTHOR"))
                .andExpect(status().isUnauthorized());
        // 未知替身签发 → 403（会话模式下替身签发端点已关闭，见下方 shut 用例）
        assertThat(issueRaw("IMPERSONATOR")).contains("A2001");
        // 过期令牌 → 401（直接置 expires_at 过去模拟超时）
        String expiring = seedSession("AUTHOR");
        jdbc.update("UPDATE auth_session SET expires_at = NOW(6) - INTERVAL 1 MINUTE "
                + "WHERE token_hash = SHA2(?, 256)", expiring);
        mockMvc.perform(get("/api/v1/me/works").header("Authorization", "Bearer " + expiring))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0001"));
    }

    @Test
    void revocationIsIdempotentAndClosureRevokesAllSessions() throws Exception {
        String token = seedSession("AUTHOR");
        // 登出 → 200；撤销后令牌失效 → 401；重复撤销幂等 200
        mockMvc.perform(post("/api/v1/auth/sessions/revocations")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revoked").value(true));
        mockMvc.perform(get("/api/v1/me/works").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/sessions/revocations")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revoked").value(true));
        // 注销联动：再签发两枚会话 → 注销 → 全部撤销
        String tokenA = seedSession("AUTHOR");
        String tokenB = seedSession("AUTHOR");
        String subject = jdbc.queryForObject(
                "SELECT subject FROM auth_session WHERE token_hash = SHA2(?, 256)",
                String.class, tokenA);
        mockMvc.perform(post("/api/v1/me/closure")
                        .header("Authorization", "Bearer " + tokenA)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me/works").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/me/works").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isUnauthorized());
        int revoked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_session WHERE subject = ? AND revoked_at IS NOT NULL",
                Integer.class, subject);
        assertThat(revoked).isGreaterThanOrEqualTo(2);
        // 审计事实不删：注销审计在
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_record WHERE resource_id = ? "
                        + "AND action = 'account.closed'", Integer.class, subject)).isEqualTo(1);
    }

    private String issueRaw(String stub) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/sessions")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{\"stub\":\"" + stub + "\"}"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void demoStubIssuanceIsClosedOutsideDemoMode() throws Exception {
        // 2026-11-20 修复：session（生产）模式下替身签发一律 403/A2001，
        // 不再存在"无凭据即可领取 ADMIN 令牌"的发卡机。
        assertThat(issueRaw("AUTHOR")).contains("A2001");
        assertThat(issueRaw("ADMIN")).contains("A2001");
        assertThat(issueRaw("HACKER")).contains("A2001");
        assertThat(issueRaw("")).contains("A2001");
        mockMvc.perform(post("/api/v1/auth/sessions")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{\"stub\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }
}
