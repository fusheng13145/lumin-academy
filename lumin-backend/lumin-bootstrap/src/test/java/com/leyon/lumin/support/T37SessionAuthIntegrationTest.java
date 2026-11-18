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

    /** 签发会话（断言 200 并返回持有令牌）。 */
    private String issue(String stub) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/sessions")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{\"stub\":\"" + stub + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subject").isNotEmpty())
                .andReturn();
        return com.leyon.lumin.support.TestResponseSupport.field(result, "token");
    }

    @Test
    void issuedSessionAuthorizesAndForgedOrMissingTokensAreRejected() throws Exception {
        String token = issue("AUTHOR");
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
        // 未知替身签发 → 401
        assertThat(issueRaw("IMPERSONATOR")).contains("A0001");
        // 过期令牌 → 401（直接置 expires_at 过去模拟超时）
        String expiring = issue("AUTHOR");
        jdbc.update("UPDATE auth_session SET expires_at = NOW(6) - INTERVAL 1 MINUTE "
                + "WHERE token_hash = SHA2(?, 256)", expiring);
        mockMvc.perform(get("/api/v1/me/works").header("Authorization", "Bearer " + expiring))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0001"));
    }

    @Test
    void revocationIsIdempotentAndClosureRevokesAllSessions() throws Exception {
        String token = issue("AUTHOR");
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
        String tokenA = issue("AUTHOR");
        String tokenB = issue("AUTHOR");
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
    void sessionIssuanceRequiresBoundedStub() throws Exception {
        // 有界集合外的替身名称一律 401（不接受任意自报身份）
        assertThat(issueRaw("HACKER")).contains("A0001");
        assertThat(issueRaw("")).contains("A0001");
    }
}
