package com.leyon.lumin.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UGC-047 免认证入口按 IP 配额（2026-11-20 安全审查 19 项）。
 *
 * <p>本类把配额压到 3 次/分钟以便低成本触发。覆盖：
 * <ol>
 *   <li>超限一律 429/A2003，且**先计数后校验**（第 4 次即便口令正确也被拦）；</li>
 *   <li>配额按 IP 独立（另一个地址不受影响）；</li>
 *   <li>登录 / 注册 / 二次验证挑战**共用同一个桶**（换端点不能续命）；</li>
 *   <li>落库口径：窗口按分钟对齐、只存 64 位加盐摘要（不含原始地址）、单 IP 单窗口一行。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "lumin.review.calibration-valid=true",
        "lumin.auth.ip-limit.attempts-per-minute=3"})
@AutoConfigureMockMvc
class UGC047IpLimitIntegrationTest extends MySqlIntegrationBase {

    private static final String STRONG = "Lumin#2026Safe";
    private static final String GOOD_IP = "203.0.113.10";
    private static final String OTHER_IP = "198.51.100.77";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private MvcResult loginFrom(String ip, String loginId, String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-sessions").with(from(ip))
                        .contentType("application/json")
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + password
                                + "\"}"))
                .andReturn();
    }

    private MvcResult registerFrom(String ip, String loginId) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/registrations").with(from(ip))
                        .contentType("application/json")
                        .content("{\"loginId\":\"" + loginId + "\",\"password\":\"" + STRONG
                                + "\",\"nickname\":\"配额读者\",\"birthDate\":\"2000-01-01\"}"))
                .andReturn();
    }

    private MvcResult challengeFrom(String ip, String challengeToken, String code) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/mfa-challenges").with(from(ip))
                        .contentType("application/json")
                        .content("{\"challengeToken\":\"" + challengeToken + "\",\"code\":\"" + code
                                + "\"}"))
                .andReturn();
    }

    @Test
    void attemptsBeyondQuotaReturn429BeforeCredentialCheck() throws Exception {
        // 前 3 次计数并正常返回凭据错误（账号不存在 → 401）
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(loginFrom(GOOD_IP, "ghost2026", STRONG).getResponse().getStatus())
                    .isEqualTo(401);
        }
        // 第 4 次：配额在凭据校验之前生效 → 429/A2003（即便口令"正确"也无从校验）
        mockMvc.perform(post("/api/v1/auth/password-sessions").with(from(GOOD_IP))
                        .contentType("application/json")
                        .content("{\"loginId\":\"ghost2026\",\"password\":\"" + STRONG + "\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("A2003"));
    }

    @Test
    void quotaIsPerClientAddress() throws Exception {
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(loginFrom(GOOD_IP, "ghost2026", STRONG).getResponse().getStatus())
                    .isEqualTo(401);
        }
        // 另一个地址有独立配额
        assertThat(loginFrom(OTHER_IP, "ghost2026", STRONG).getResponse().getStatus())
                .isEqualTo(401);
        // 原地址已超限
        assertThat(loginFrom(GOOD_IP, "ghost2026", STRONG).getResponse().getStatus())
                .isEqualTo(429);
        // 库里因此是两行（每个 (ip, 窗口) 一行）
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_ip_throttle", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void loginRegistrationAndChallengeShareOneBucket() throws Exception {
        // 3 次注册（计数，与是否成功无关）
        registerFrom(GOOD_IP, "ipquota1");
        registerFrom(GOOD_IP, "ipquota2");
        registerFrom(GOOD_IP, "ipquota3");
        // 换成二次验证挑战端点：同一个桶已满 → 429（换端点不能续命）
        mockMvc.perform(post("/api/v1/auth/mfa-challenges").with(from(GOOD_IP))
                        .contentType("application/json")
                        .content("{\"challengeToken\":\"bogus\",\"code\":\"123456\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("A2003"));
        // 被拒的那次随事务回滚不落库：库里停在"恰好达上限"（3），窗口内继续被拒
        assertThat(jdbc.queryForObject(
                "SELECT attempts FROM auth_ip_throttle", Integer.class)).isEqualTo(3);
    }

    @Test
    void challengeEndpointAloneIsAlsoQuotaLimited() throws Exception {
        assertThat(challengeFrom(OTHER_IP, "bogus", "123456").getResponse().getStatus())
                .isEqualTo(401);
        assertThat(challengeFrom(OTHER_IP, "bogus", "123456").getResponse().getStatus())
                .isEqualTo(401);
        assertThat(challengeFrom(OTHER_IP, "bogus", "123456").getResponse().getStatus())
                .isEqualTo(401);
        mockMvc.perform(post("/api/v1/auth/mfa-challenges").with(from(OTHER_IP))
                        .contentType("application/json")
                        .content("{\"challengeToken\":\"bogus\",\"code\":\"123456\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void windowIsMinuteAlignedAndAddressIsHashed() throws Exception {
        loginFrom(GOOD_IP, "ghost2026", STRONG);
        String ipHash = jdbc.queryForObject("SELECT ip_hash FROM auth_ip_throttle",
                String.class);
        Integer attempts = jdbc.queryForObject("SELECT attempts FROM auth_ip_throttle",
                Integer.class);
        java.sql.Timestamp windowStamp = jdbc.queryForObject(
                "SELECT window_start FROM auth_ip_throttle", java.sql.Timestamp.class);
        assertThat(ipHash).hasSize(64).doesNotContain("203.0.113.10");
        assertThat(attempts).isEqualTo(1);
        LocalDateTime windowStart = windowStamp.toLocalDateTime();
        // 窗口按分钟对齐（秒与纳秒为 0）：同一分钟的尝试落在同一行，跨分钟自动换行
        assertThat(windowStart.getSecond()).isZero();
        assertThat(windowStart.getNano()).isZero();
    }
}
