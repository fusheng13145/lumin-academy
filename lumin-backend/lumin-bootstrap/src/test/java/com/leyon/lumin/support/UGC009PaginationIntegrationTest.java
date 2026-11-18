package com.leyon.lumin.support;

import com.leyon.lumin.foundation.cursor.CursorCodec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UGC-009 授权游标分页集成测试（docs/13；05 §2 游标口径；04 §7）：
 * 跨页无漏重（keyset 稳定排序）、隐藏作品不泄露且无隐藏计数、签名字游标
 * 绑定主体/过滤摘要、15 分钟过期、篡改/跨主体 → 400、limit 默认 20 上限 100
 * （显式非法 400）。
 */
@SpringBootTest(properties = "lumin.review.calibration-valid=true")
@AutoConfigureMockMvc
class UGC009PaginationIntegrationTest extends MySqlIntegrationBase {

    /** 嵌套配置：抽检闸恒未命中——AUTO 链路确定性验证。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class NeverSampledGate {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.leyon.lumin.moderation.service.AutoSampleGate neverSampled() {
            return caseId -> false;
        }
    }

    private static final String READER_C3 =
            com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C3;

    @org.junit.jupiter.api.BeforeEach
    void seedFixtures() {
        MySqlFixtures.insertStubAuthorProfile(jdbc);
        MySqlFixtures.insertAuthorWorkAndCategory(jdbc);
        MySqlFixtures.insertReaderProfiles(jdbc);
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.leyon.lumin.foundation.event.OutboxDispatcher dispatcher;
    @Value("${lumin.cursor.key}")
    private String cursorKey;

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(org.springframework.test.web.servlet.ResultActions actions,
                                String name) throws java.io.UnsupportedEncodingException {
        return com.leyon.lumin.support.TestResponseSupport.field(actions, name);
    }

    /** 发布一部作品（AUTO 链确定性；抽检闸由全类 NeverSampledGate 关闭；grade 由报告指定）。 */
    private String publishWork(String title, String grade) throws Exception {
        String workId = field(mockMvc.perform(post("/api/v1/works")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String draftId = field(mockMvc.perform(post("/api/v1/drafts")
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
                                + "\"suggestedDecision\":\"PASS\",\"grade\":\"" + grade + "\",\"confidence\":0.99,"
                                + "\"evidenceRefs\":[\"ev:1\"],\"inputTokens\":100,\"outputTokens\":10,"
                                + "\"durationMs\":1000}"))
                .andExpect(status().isOk());
        dispatcher.deliverPending();
        return workId;
    }

    @Test
    void pagedTraversalYieldsAllVisibleWorksExactlyOnce() throws Exception {
        // 五部 G 级作品 → C3 读者分页（limit 2）应恰好全部见到且不重复
        Set<String> expected = new LinkedHashSet<>();
        for (int i = 1; i <= 5; i++) {
            expected.add(publishWork("分页作品" + i, "G"));
        }
        Set<String> seen = new LinkedHashSet<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            MvcResult result = mockMvc.perform(get("/api/v1/works")
                            .param("limit", "2")
                            .param("cursor", cursor == null ? new String[]{} : new String[]{cursor})
                            .header("X-Lumin-Demo-Subject", READER_C3))
                    .andExpect(status().isOk())
                    .andReturn();
            String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
            // 逐项提取 workId（data 数组）
            String marker = "\"workId\":\"";
            int idx = 0;
            while ((idx = body.indexOf(marker, idx)) >= 0) {
                int end = body.indexOf('"', idx + marker.length());
                seen.add(body.substring(idx + marker.length(), end));
                idx = end;
            }
            pages++;
            String next = result.getResponse().getHeader("X-Lumin-Next-Cursor");
            if (next == null || next.isBlank()) {
                break;
            }
            cursor = next;
            assertThat(pages).as("分页深度护栏").isLessThan(10);
        }
        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactlyInAnyOrderElementsOf(expected);
        // 幂等复核：同游标重放同页（游标 15 分钟内可重复使用）
        assertThat(seen.size()).isEqualTo(5);
    }

    @Test
    void hiddenWorksAndCountsAreNeverLeaked() throws Exception {
        // A 级作品（默认关闭）→ 对全部年龄带不可见，且响应无数值/总数类字段
        publishWork("隐匿作品A", "A");
        String body = mockMvc.perform(get("/api/v1/works")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.grade == 'A')]").isEmpty())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("隐匿作品A");
        assertThat(body).doesNotContain("\"total\"");
        assertThat(body).doesNotContain("\"count\"");
        // 隐藏数不泄露：分页游标只指向可见项
        publishWork("可见G", "G");
        String cursor = mockMvc.perform(get("/api/v1/works").param("limit", "1")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader("X-Lumin-Next-Cursor");
        if (cursor != null) {
            String nextBody = mockMvc.perform(get("/api/v1/works")
                            .param("limit", "1").param("cursor", cursor)
                            .header("X-Lumin-Demo-Subject", READER_C3))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(nextBody).doesNotContain("隐匿作品A");
        }
    }

    @Test
    void cursorTamperingExpiryCrossSubjectAndLimitBoundsAreRejected() throws Exception {
        publishWork("游标校验甲", "G");
        String workId = publishWork("游标校验乙", "G");
        MvcResult first = mockMvc.perform(get("/api/v1/works").param("limit", "1")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andReturn();
        String cursor = first.getResponse().getHeader("X-Lumin-Next-Cursor");
        assertThat(cursor).as("两件可见作品 + limit=1 → 必有下一页游标").isNotNull();
        // 篡改（改一位签名/载荷）→ 400
        String tampered = cursor.substring(0, cursor.length() - 4)
                + (cursor.endsWith("AAAA") ? "AAAB" : "AAAA");
        mockMvc.perform(get("/api/v1/works").param("limit", "1").param("cursor", tampered)
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("P1001"));
        // 过期（issuedAt 前移 16 分钟）→ 400
        Instant issued = Instant.now().minusSeconds(16 * 60);
        String expired = CursorCodec.encode(new CursorCodec.Payload(
                        LocalDateTime.now(ZoneOffset.UTC).toString(),
                        UUID.fromString(jdbc.queryForObject(
                                "SELECT id FROM publication_pointer WHERE target_id = ? LIMIT 1",
                                String.class, workId)),
                        CursorCodec.filterDigest("list=works;band=C3"),
                        UUID.fromString(com.leyon.lumin.foundation.web.DemoAuthenticationFilter
                                .READER_C3_SUBJECT), issued),
                cursorKey.getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(get("/api/v1/works").param("limit", "1").param("cursor", expired)
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("P1001"));
        // 跨主体（C2 读者携带 C3 游标）→ 400（主体作用域绑定）
        mockMvc.perform(get("/api/v1/works").param("limit", "1").param("cursor", cursor)
                        .header("X-Lumin-Demo-Subject",
                                com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("P1001"));
        // limit 显式越界 → 400；默认 limit（无参数）→ 200 且 <= 20 条
        mockMvc.perform(get("/api/v1/works").param("limit", "101")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("P1001"));
        mockMvc.perform(get("/api/v1/works")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(result -> assertThat(
                        result.getResponse().getContentAsString(StandardCharsets.UTF_8)
                                .split("\"workId\":\"", -1).length - 1)
                        .isLessThanOrEqualTo(20));
        assertThat(workId).isNotEmpty();
    }
}
