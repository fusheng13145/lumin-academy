package com.leyon.lumin.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leyon.lumin.support.mail.SmtpSink;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UGC-050 真实 SMTP 投递闭环（V24 收尾；2026-11-20 审查 04 项"邮件通道必须有"）。
 *
 * <p>与 `UGC048` 的区别：那里用记录型网关证明**业务链路**，这里用最小 SMTP 收件端 +
 * 真实 `SmtpPasswordResetMailGateway` + `mail-mode=smtp` 装配，证明**码真的躺进收件箱**，
 * 且从邮件正文读出的码能直接完成改密。整条链路没有任何 mock。
 */
@SpringBootTest(properties = {
        "lumin.review.calibration-valid=true",
        "lumin.auth.reset.mail-mode=smtp"})
@AutoConfigureMockMvc
class UGC050SmtpPasswordResetIntegrationTest extends MySqlIntegrationBase {

    private static final String STRONG = "Lumin#2026Safe";
    private static final String NEW_STRONG = "Shuyuan#2026Mail";
    private static final String LOGIN_ID = "smtp2026";
    private static final String EMAIL = "smtp2026@example.com";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern CODE = Pattern.compile("[A-Z2-9]{5}-[A-Z2-9]{5}");

    private static SmtpSink sink;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void startSink() throws Exception {
        sink = new SmtpSink();
    }

    @AfterAll
    static void stopSink() {
        sink.close();
    }

    /** 把真实 SMTP 参数喂给 Spring 上下文（端口在测试启动时才确定）。 */
    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("lumin.auth.reset.mail.host", () -> "127.0.0.1");
        registry.add("lumin.auth.reset.mail.port", () -> sink.port());
        registry.add("lumin.auth.reset.mail.from", () -> "no-reply@qiming.example");
        registry.add("lumin.auth.reset.mail.starttls", () -> false);
        registry.add("lumin.auth.reset.mail.ssl", () -> false);
        registry.add("lumin.auth.reset.mail.timeout-seconds", () -> 5);
    }

    @BeforeEach
    void clearMailbox() {
        sink.messages().clear();
    }

    @Test
    void resetCodeArrivesByRealSmtpAndWorks() throws Exception {
        JsonNode registered = register();
        String oldToken = registered.get("token").asText();

        MvcResult requested = mockMvc.perform(post("/api/v1/auth/password-resets")
                        .contentType("application/json")
                        .content("{\"loginId\":\"" + LOGIN_ID + "\",\"channel\":\"EMAIL\"}"))
                .andReturn();
        assertThat(requested.getResponse().getStatus()).isEqualTo(200);

        // 同步投递：响应返回时邮件已进收件端
        String raw = sink.awaitMessage(1, Duration.ofSeconds(3));
        assertThat(SmtpSink.header(raw, "To")).isEqualTo(EMAIL);

        String body = SmtpSink.decodedBody(raw);
        Matcher matcher = CODE.matcher(body);
        assertThat(matcher.find()).as("邮件正文必须含 5-5 分段的重置码").isTrue();
        String code = matcher.group();

        // 数据库只有摘要，且不等于明文
        String hash = jdbc.queryForObject(
                "SELECT code_hash FROM password_reset_ticket WHERE login_id = ?",
                String.class, LOGIN_ID);
        assertThat(hash).hasSize(64).doesNotContain(code.replace("-", ""));

        // 邮件里的码真的能改密
        assertThat(confirm(code, NEW_STRONG).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM password_reset_ticket WHERE login_id = ?",
                String.class, LOGIN_ID)).isEqualTo("CONSUMED");

        // 旧会话立刻失效；旧口令登不上，新口令可登录
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());
        assertThat(login(STRONG).getResponse().getStatus()).isEqualTo(401);
        assertThat(login(NEW_STRONG).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void smtpModeStartsWithRealGateway() {
        // 上下文本身能起来 = mail-mode=smtp + 真实网关通过了启动门禁
        assertThat(sink.port()).isPositive();
        assertThat(sink.messages()).isEmpty();
    }

    private JsonNode register() throws Exception {
        String body = "{\"loginId\":\"" + LOGIN_ID + "\",\"password\":\"" + STRONG
                + "\",\"nickname\":\"邮件读者\",\"birthDate\":\"2000-01-01\","
                + "\"email\":\"" + EMAIL + "\"}";
        MvcResult result = mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType("application/json").content(body))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as(body).isEqualTo(200);
        return data(result);
    }

    private MvcResult confirm(String code, String newPassword) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-reset-confirmations")
                        .contentType("application/json")
                        .content("{\"loginId\":\"" + LOGIN_ID + "\",\"code\":\"" + code
                                + "\",\"newPassword\":\"" + newPassword + "\"}"))
                .andReturn();
    }

    private MvcResult login(String password) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password-sessions")
                        .contentType("application/json")
                        .content("{\"loginId\":\"" + LOGIN_ID + "\",\"password\":\"" + password
                                + "\"}"))
                .andReturn();
    }

    private static JsonNode data(MvcResult result) throws Exception {
        return MAPPER.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("data");
    }
}
