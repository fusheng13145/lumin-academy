package com.leyon.lumin.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UGC-045 生产模式（{@code lumin.auth.mode=session}）下真实凭据链路集成测试。
 *
 * <p>验证"必须先修"第 2 项的闸门语义：真实口令注册/登录在生产模式下可用，
 * 演示身份头与替身发卡机全部关闭；改密端点必须已登录（未登录 401）。
 */
@SpringBootTest(properties = {"lumin.review.calibration-valid=true",
        "lumin.auth.mode=session"})
@AutoConfigureMockMvc
class UGC045SessionModeCredentialIntegrationTest extends MySqlIntegrationBase {

    private static final String STRONG = "Lumin#2026Safe";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void realCredentialsWorkWhileDemoPathsAreClosed() throws Exception {
        // 注册（豁免端点）在生产模式下可用
        var registered = mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType("application/json")
                        .content("{\"loginId\":\"prod2026\",\"password\":\"" + STRONG
                                + "\",\"nickname\":\"生产用户\",\"birthDate\":\"1998-05-06\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn();
        String token = TestResponseSupport.field(registered, "token");
        // 凭口令签发的令牌可用（服务端权威主体）
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        // 演示身份头在生产模式下被忽略
        mockMvc.perform(get("/api/v1/me").header("X-Lumin-Demo-Subject", "ADMIN"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0001"));
        // 替身发卡机已关闭（不再存在无凭据领取 ADMIN 令牌的入口）
        mockMvc.perform(post("/api/v1/auth/sessions")
                        .header("Idempotency-Key", java.util.UUID.randomUUID().toString())
                        .contentType("application/json").content("{\"stub\":\"ADMIN\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A2001"));
    }

    @Test
    void passwordChangeRequiresLogin() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password-changes")
                        .contentType("application/json")
                        .content("{\"currentPassword\":\"" + STRONG
                                + "\",\"newPassword\":\"Shuyuan#2026Key\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("A0001"));
    }

    @Test
    void loginWithCorrectPasswordIssuesSession() throws Exception {
        mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType("application/json")
                        .content("{\"loginId\":\"prodlogin\",\"password\":\"" + STRONG
                                + "\",\"nickname\":\"登录用户\",\"birthDate\":\"1995-03-04\"}"))
                .andExpect(status().isOk());
        var loggedIn = mockMvc.perform(post("/api/v1/auth/password-sessions")
                        .contentType("application/json")
                        .content("{\"loginId\":\"prodlogin\",\"password\":\"" + STRONG + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = TestResponseSupport.field(loggedIn, "token");
        assertThat(token).contains(".");
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
