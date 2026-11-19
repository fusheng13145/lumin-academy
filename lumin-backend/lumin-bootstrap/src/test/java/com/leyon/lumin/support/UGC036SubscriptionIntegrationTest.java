package com.leyon.lumin.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UGC-036 追更订阅集成测试（docs/13 M1；docs/04 §6.1 subscription；V17）：
 * 订阅幂等（重复订阅重开不报错）；退订幂等；跨账号隔离；收藏≠订阅；
 * 免打扰窗口（跨午夜合法、start==end 拒绝）与 IANA 时区校验（400）；
 * 未成年/未核实推送闸 fail-closed（C1 请求开推送 → 生效值 false）；
 * 未订阅偏好更新 404。
 */
@SpringBootTest(properties = "lumin.review.calibration-valid=true")
@AutoConfigureMockMvc
class UGC036SubscriptionIntegrationTest extends MySqlIntegrationBase {

    /** 嵌套配置：抽检闸恒未命中——AUTO 链路确定性验证（抽检函数另有单测）。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class NeverSampledGate {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.leyon.lumin.moderation.service.AutoSampleGate neverSampled() {
            return caseId -> false;
        }
    }

    private static final String AUTHOR = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.AUTHOR;
    private static final String READER_C3 = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C3;
    private static final String READER_C1 = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C1;
    /** jdbc 断言用主体 UUID（header 传别名，落库是 SUBJECT）。 */
    private static final String SUBJECT_C3 =
            com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C3_SUBJECT;
    private static final String SUBJECT_C1 =
            com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C1_SUBJECT;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.leyon.lumin.foundation.event.OutboxDispatcher dispatcher;

    @BeforeEach
    void seedFixtures() {
        MySqlFixtures.insertStubAuthorProfile(jdbc);
        MySqlFixtures.insertAuthorWorkAndCategory(jdbc);
        MySqlFixtures.insertReaderProfiles(jdbc);
    }

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(org.springframework.test.web.servlet.ResultActions actions,
                                String name) throws java.io.UnsupportedEncodingException {
        return com.leyon.lumin.support.TestResponseSupport.field(actions, name);
    }

    /** 发布一部 G 级作品（发布流经真实 API + 机审报告，列表断言才有标题元数据）。 */
    private String publishWork(String title) throws Exception {
        String workId = field(mockMvc.perform(post("/api/v1/works")
                        .header("X-Lumin-Demo-Subject", AUTHOR)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String draftId = field(mockMvc.perform(post("/api/v1/drafts")
                        .header("X-Lumin-Demo-Subject", AUTHOR)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"title\":\"" + title + "\","
                                + "\"synopsis\":\"简介\",\"categoryId\":\""
                                + MySqlFixtures.CATEGORY_LITERATURE + "\",\"tags\":[]}"))
                .andExpect(status().isOk()), "id");
        mockMvc.perform(post("/api/v1/drafts/" + draftId + "/submissions")
                        .header("Idempotency-Key", uuidKey())
                        .header("If-Match", "\"1\"")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isAccepted());
        dispatcher.deliverPending();
        String workflowId = jdbc.queryForObject(
                "SELECT id FROM publication_workflow WHERE version_id = (SELECT id FROM "
                        + "content_version WHERE source_draft_id = ?)", String.class, draftId);
        String caseId = jdbc.queryForObject(
                "SELECT case_id FROM publication_workflow WHERE id = ?", String.class, workflowId);
        String versionId = jdbc.queryForObject(
                "SELECT version_id FROM publication_workflow WHERE id = ?", String.class,
                workflowId);
        String materialSha = jdbc.queryForObject(
                "SELECT material_sha256 FROM content_version WHERE id = ?", String.class,
                versionId);
        mockMvc.perform(post("/internal/v1/machine-reports")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"caseId\":\"" + caseId + "\",\"workflowId\":\"" + workflowId
                                + "\",\"versionId\":\"" + versionId + "\",\"policyVersion\":1,"
                                + "\"materialSha256\":\"" + materialSha
                                + "\",\"modelVersion\":\"guard-v1\",\"risk\":\"LOW\",\"riskFlags\":[],"
                                + "\"suggestedDecision\":\"PASS\",\"grade\":\"G\",\"confidence\":0.99,"
                                + "\"evidenceRefs\":[\"ev:1\"],\"inputTokens\":100,\"outputTokens\":10,"
                                + "\"durationMs\":1000}"))
                .andExpect(status().isOk());
        dispatcher.deliverPending();
        return workId;
    }

    private ResultActions subscribe(String subject, String workId) throws Exception {
        return mockMvc.perform(post("/api/v1/me/subscriptions")
                .header("X-Lumin-Demo-Subject", subject)
                .header("Idempotency-Key", uuidKey())
                .contentType("application/json")
                .content("{\"workId\":\"" + workId + "\"}"));
    }

    private ResultActions putPreferences(String subject, String workId, String quietStart,
                                         String quietEnd, String timezoneId,
                                         boolean pushEnabled) throws Exception {
        return mockMvc.perform(put("/api/v1/me/subscriptions/" + workId + "/preferences")
                .header("X-Lumin-Demo-Subject", subject)
                .header("Idempotency-Key", uuidKey())
                .contentType("application/json")
                .content("{\"quietStart\":\"" + quietStart + "\",\"quietEnd\":\"" + quietEnd
                        + "\",\"timezoneId\":\"" + timezoneId
                        + "\",\"pushEnabled\":" + pushEnabled + "}"));
    }

    @Test
    void subscribeIsIdempotentAndListShowsDefaults() throws Exception {
        String workId = publishWork("追更作品");
        subscribe(READER_C3, workId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subscribed").value(true));
        // 重复订阅：重开不报错、不产生第二行
        subscribe(READER_C3, workId).andExpect(status().isOk());
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM subscription WHERE user_id = ? AND work_id = ?",
                Integer.class, SUBJECT_C3, workId);
        assertThat(rows).isEqualTo(1);

        mockMvc.perform(get("/api/v1/me/subscriptions")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].workId").value(workId))
                .andExpect(jsonPath("$.data[0].title").value("追更作品"))
                .andExpect(jsonPath("$.data[0].workState").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].quietStart").value("22:00"))
                .andExpect(jsonPath("$.data[0].quietEnd").value("08:00"))
                .andExpect(jsonPath("$.data[0].pushEnabled").value(false))
                .andExpect(jsonPath("$.data[0].timezoneId").value("UTC"));

        mockMvc.perform(get("/api/v1/me/subscriptions/" + workId + "/status")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subscribed").value(true));
    }

    @Test
    void bookmarkDoesNotImplySubscription() throws Exception {
        String workId = publishWork("收藏不订阅作品");
        // 收藏 ≠ 订阅：加书架不产生订阅行；删书架不影响订阅
        mockMvc.perform(post("/api/v1/me/bookmarks")
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me/subscriptions/" + workId + "/status")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subscribed").value(false));

        subscribe(READER_C3, workId).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/me/bookmarks/" + workId)
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/me/subscriptions/" + workId + "/status")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subscribed").value(true));
    }

    @Test
    void verifiedAdultMayEnablePushAndCustomWindow() throws Exception {
        String workId = publishWork("成年推送作品");
        subscribe(READER_C3, workId).andExpect(status().isOk());
        putPreferences(READER_C3, workId, "20:00", "07:00", "Asia/Shanghai", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pushEnabled").value(true))
                .andExpect(jsonPath("$.data.quietStart").value("20:00"))
                .andExpect(jsonPath("$.data.quietEnd").value("07:00"))
                .andExpect(jsonPath("$.data.timezoneId").value("Asia/Shanghai"));

        var row = jdbc.queryForMap(
                "SELECT push_enabled, timezone_id FROM subscription "
                        + "WHERE user_id = ? AND work_id = ?", SUBJECT_C3, workId);
        assertThat(Boolean.TRUE.equals(row.get("push_enabled"))).isTrue();
        assertThat(row.get("timezone_id")).isEqualTo("Asia/Shanghai");
    }

    @Test
    void minorPushRequestIsFailClosedToDisabled() throws Exception {
        // 未成年（C1）请求开推送：生效值收窄为 false（同意范围随 UGC-003 放宽）
        String workId = publishWork("未成年推送作品");
        subscribe(READER_C1, workId).andExpect(status().isOk());
        putPreferences(READER_C1, workId, "22:00", "08:00", "Asia/Shanghai", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pushEnabled").value(false));

        Integer pushEnabled = jdbc.queryForObject(
                "SELECT push_enabled FROM subscription WHERE user_id = ? AND work_id = ?",
                Integer.class, SUBJECT_C1, workId);
        assertThat(pushEnabled).isEqualTo(0);
    }

    @Test
    void invalidWindowOrZoneIsRejected() throws Exception {
        String workId = publishWork("偏好校验作品");
        subscribe(READER_C3, workId).andExpect(status().isOk());
        // start == end：空窗口拒绝
        putPreferences(READER_C3, workId, "08:00", "08:00", "UTC", false)
                .andExpect(status().isBadRequest());
        // 格式非法
        putPreferences(READER_C3, workId, "bad", "08:00", "UTC", false)
                .andExpect(status().isBadRequest());
        // 非法 IANA 时区
        putPreferences(READER_C3, workId, "22:00", "08:00", "Mars/Phobos", false)
                .andExpect(status().isBadRequest());
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM subscription WHERE user_id = ? AND work_id = ?",
                Integer.class, SUBJECT_C3, workId);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void preferencesWithoutSubscriptionIsNotFound() throws Exception {
        String workId = publishWork("未订阅作品");
        putPreferences(READER_C3, workId, "22:00", "08:00", "UTC", false)
                .andExpect(status().isNotFound());
    }

    @Test
    void unsubscribeIsIdempotentAndAccountIsolated() throws Exception {
        String workId = publishWork("退订隔离作品");
        subscribe(READER_C3, workId).andExpect(status().isOk());
        subscribe(READER_C1, workId).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/me/subscriptions/" + workId)
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subscribed").value(false));
        // 重复退订幂等
        mockMvc.perform(delete("/api/v1/me/subscriptions/" + workId)
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/me/subscriptions")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        // 跨账号隔离：C3 退订不影响 C1 订阅
        mockMvc.perform(get("/api/v1/me/subscriptions/" + workId + "/status")
                        .header("X-Lumin-Demo-Subject", READER_C1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.subscribed").value(true));
    }
}
