package com.leyon.lumin.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UGC-029 限流边界集成测试（docs/13；O25；6.9 发布频控/举报限流）：
 * 写操作按主体+路径组滑动窗口限流——限内通过、超限 429/A2003 + Retry-After；
 * 主体隔离（A 超限不影响 B）；读方法不限；不同限值经独立上下文（本类
 * writes-per-minute=3）。
 */
@SpringBootTest(properties = {"lumin.review.calibration-valid=true",
        "lumin.rate-limit.enabled=true", "lumin.rate-limit.writes-per-minute=3"})
@AutoConfigureMockMvc
class UGC029RateLimitIntegrationTest extends MySqlIntegrationBase {

    private static final String AUTHOR = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.AUTHOR;
    private static final String READER_C3 = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C3;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void seedFixtures() {
        MySqlFixtures.insertStubAuthorProfile(jdbc);
        MySqlFixtures.insertReaderProfiles(jdbc);
    }

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    @Test
    void writesOverLimitAreRejectedWith429AndSubjectIsolated() throws Exception {
        // 前 3 次写（同一资源组 works）→ 200
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/v1/works")
                            .header("X-Lumin-Demo-Subject", AUTHOR)
                            .header("Idempotency-Key", uuidKey())
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isOk());
        }
        // 第 4 次写 → 429/A2003 + Retry-After
        MvcResult limited = mockMvc.perform(post("/api/v1/works")
                        .header("X-Lumin-Demo-Subject", AUTHOR)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("A2003"))
                .andExpect(header().exists("Retry-After"))
                .andReturn();
        assertThat(Integer.parseInt(
                limited.getResponse().getHeader("Retry-After"))).isBetween(1, 60);
        // 主体隔离：B 主体不受 A 的配额影响
        mockMvc.perform(post("/api/v1/works")
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        // 读方法不限
        mockMvc.perform(get("/api/v1/works").header("X-Lumin-Demo-Subject", AUTHOR))
                .andExpect(status().isOk());
        // 429 信封不含内部细节
        assertThat(limited.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("Exception").doesNotContain("at com.leyon");
    }
}
