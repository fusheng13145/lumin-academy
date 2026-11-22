package com.leyon.lumin.foundation.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;

/**
 * CORS 白名单取证（2026-11-20 审查 16 项）。
 *
 * <p>断言三件事：允许来源的预检被**在认证之前**应答、非白名单来源被拒、
 * 普通请求（非预检）也带上允许头但业务鉴权不受影响。
 */
@SpringBootTest(properties = {
        "lumin.review.calibration-valid=true",
        // session 模式：没有 Bearer 就应 401，避免 demo 头把匿名请求"认成"AUTHOR
        "lumin.auth.mode=session",
        "lumin.cors.allowed-origins=https://app.qiming.example"})
@AutoConfigureMockMvc
class CorsPolicyTest {

    private static final String ALLOWED = "https://app.qiming.example";
    private static final String DENIED = "https://evil.example";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void preflightFromAllowedOriginIsAnsweredBeforeAuthentication() throws Exception {
        mockMvc.perform(options("/api/v1/me")
                        .header("Origin", ALLOWED)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers",
                                "authorization,content-type,idempotency-key"))
                .andExpect(result -> {
                    assertThat(result.getResponse().getStatus()).isEqualTo(200);
                    assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin"))
                            .isEqualTo(ALLOWED);
                    assertThat(result.getResponse().getHeader("Access-Control-Allow-Methods"))
                            .contains("GET");
                    assertThat(result.getResponse()
                            .getHeader("Access-Control-Allow-Headers"))
                            .containsIgnoringCase("authorization");
                });
    }

    @Test
    void preflightFromUnknownOriginIsRejected() throws Exception {
        mockMvc.perform(options("/api/v1/me")
                        .header("Origin", DENIED)
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(result -> {
                    assertThat(result.getResponse().getStatus()).isEqualTo(403);
                    assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin"))
                            .isNull();
                });
    }

    @Test
    void plainRequestKeepsCorsHeaderAndStillRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Origin", ALLOWED))
                .andExpect(result -> {
                    // 允许来源仍会拿到 ACAO；但没令牌照样 401（CORS 不等于鉴权）
                    assertThat(result.getResponse().getHeader("Access-Control-Allow-Origin"))
                            .isEqualTo(ALLOWED);
                    assertThat(result.getResponse().getStatus()).isEqualTo(401);
                });
    }

    @Test
    void wildcardOriginIsNeverAccepted() {
        // 配置里写 * 会被丢弃（宁可少放行，不做通配）
        assertThat(CorsPolicyConfig.parseOrigins("*")).isEmpty();
        assertThat(CorsPolicyConfig.parseOrigins("* , https://a.example , "))
                .containsExactly("https://a.example");
    }
}
