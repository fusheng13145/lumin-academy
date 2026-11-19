package com.leyon.lumin.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UGC-040 可信看板集成测试（docs/13 M1 一阶段）：冻结口径 v1——有效阅读
 * 剔除合成流量（oidc_subject `sub-` 前缀）且窗口外不计、次周留存按首读周
 * 分群（空分群不产出——无数据不编数字）、供给健康计数；角色闸
 * OPERATOR/ADMIN；窗口越界 400。
 */
@SpringBootTest(properties = "lumin.review.calibration-valid=true")
@AutoConfigureMockMvc
class UGC040MetricsIntegrationTest extends MySqlIntegrationBase {

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
    private static final String OPERATOR = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.OPERATOR;
    private static final String ADMIN = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.ADMIN;
    private static final String READER_C3 = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C3;

    private static final String CV_ID =
            UUID.fromString("7a7a7a7a-0000-4000-8000-00000000c101").toString();
    private static final String CH_2 =
            UUID.fromString("7a7a7a7a-0000-4000-8000-00000000c102").toString();

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
        // 看板专用内容版本与第二章节（进度行外键目标）
        jdbc.update("""
                INSERT INTO content_version (id, work_id, chapter_id, target_type,
                    revision_no, title, state, grade, material_sha256, material_snapshot,
                    tags, policy_version, created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, ?, 'WORK', 1, '看板章节', 'APPROVED', 'G',
                    ?, '{}', '[]', 1, ?, ?, ?, ?)
                """, CV_ID, MySqlFixtures.WORK_W, MySqlFixtures.CHAPTER_C,
                "0".repeat(64), MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM);
        jdbc.update("""
                INSERT INTO chapter (id, work_id, sequence_no, business_state,
                    created_at, updated_at, created_by, updated_by)
                VALUES (?, ?, 2, 'ACTIVE', ?, ?, ?, ?)
                """, CH_2, MySqlFixtures.WORK_W, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM);
    }

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    private static String field(org.springframework.test.web.servlet.ResultActions actions,
                                String name) throws java.io.UnsupportedEncodingException {
        return com.leyon.lumin.support.TestResponseSupport.field(actions, name);
    }

    /** 读者档案（real 为真实口径，synth 为被剔除的合成流量）。 */
    private String insertProfile(String oidcSubject, String band) {
        String id = UUID.nameUUIDFromBytes(oidcSubject.getBytes()).toString();
        jdbc.update("""
                INSERT IGNORE INTO user_profile (id, oidc_issuer, oidc_subject, nickname,
                    declared_age_band, effective_age_band, account_status,
                    policy_revision, created_at, updated_at, created_by, updated_by)
                VALUES (?, 'https://id.example', ?, ?, ?, ?, 'ACTIVE', 1, ?, ?, ?, ?)
                """, id, oidcSubject, oidcSubject, band, band,
                MySqlFixtures.BUSINESS_TIME, MySqlFixtures.BUSINESS_TIME,
                MySqlFixtures.SYSTEM, MySqlFixtures.SYSTEM);
        return id;
    }

    /** 进度触点（每用户每章节一行——唯一约束口径）。 */
    private void insertProgress(String userId, String chapterId, Instant at) {
        LocalDateTime ts = LocalDateTime.ofInstant(at, ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO reading_progress (id, user_id, chapter_id, version_id,
                    position_basis_points, read_seconds, client_updated_at, created_at,
                    updated_at, created_by, updated_by)
                VALUES (?, ?, ?, ?, 5000, 60, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), userId, chapterId, CV_ID,
                ts, ts, ts, userId, userId);
    }

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

    @Test
    void roleGateRejectsReaders() throws Exception {
        mockMvc.perform(get("/api/v1/metrics/reading")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/metrics/supply")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isForbidden());
    }

    @Test
    void readingMetricsExcludeSyntheticAndOutOfWindow() throws Exception {
        Instant now = Instant.now();
        // 合成流量（sub- 前缀）：窗口内但不计
        insertProgress(insertProfile("sub-synth-a", "C1"), MySqlFixtures.CHAPTER_C,
                now.minus(Duration.ofDays(1)));
        // 真实读者：窗口内两行（不同章节）+ 窗口外一行
        String r1 = insertProfile("real-r1", "C2");
        String r2 = insertProfile("real-r2", "C3");
        insertProgress(r1, MySqlFixtures.CHAPTER_C, now.minus(Duration.ofDays(1)));
        insertProgress(r1, CH_2, now.minus(Duration.ofDays(2)));
        insertProgress(r2, MySqlFixtures.CHAPTER_C, now.minus(Duration.ofDays(1)));
        // 窗口外（40 天前）不计
        insertProgress(insertProfile("real-r3", "C1"), MySqlFixtures.CHAPTER_C,
                now.minus(Duration.ofDays(40)));

        MvcResult result = mockMvc.perform(get("/api/v1/metrics/reading")
                        .param("days", "7")
                        .header("X-Lumin-Demo-Subject", OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.frozenDefinitionVersion").value(1))
                .andExpect(jsonPath("$.data.effectiveReads").value(3))
                .andExpect(jsonPath("$.data.syntheticExcluded").value(1))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("\"band\":\"C2\"", "\"reads\":2");
        assertThat(body).contains("\"band\":\"C3\"", "\"reads\":1");
        // ADMIN 同权
        mockMvc.perform(get("/api/v1/metrics/reading")
                        .header("X-Lumin-Demo-Subject", ADMIN))
                .andExpect(status().isOk());
    }

    @Test
    void windowBoundsAreValidated() throws Exception {
        mockMvc.perform(get("/api/v1/metrics/reading")
                        .param("days", "0")
                        .header("X-Lumin-Demo-Subject", OPERATOR))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/metrics/reading")
                        .param("days", "200")
                        .header("X-Lumin-Demo-Subject", OPERATOR))
                .andExpect(status().isBadRequest());
        // 默认 7 天
        mockMvc.perform(get("/api/v1/metrics/reading")
                        .header("X-Lumin-Demo-Subject", OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.windowDays").value(7));
    }

    @Test
    void retentionCohortCountsNextWeekReturn() throws Exception {
        Instant now = Instant.now();
        Instant thisWeek = now.minus(Duration.ofDays(1));
        Instant lastWeek = now.minus(Duration.ofDays(8));
        // 首读都在上周（同一自然周）：r1/r2/r3；仅 r1 本周回归
        String r1 = insertProfile("real-ret-1", "C2");
        String r2 = insertProfile("real-ret-2", "C3");
        String r3 = insertProfile("real-ret-3", "C1");
        insertProgress(r1, MySqlFixtures.CHAPTER_C, lastWeek);
        insertProgress(r1, CH_2, thisWeek);
        insertProgress(r2, MySqlFixtures.CHAPTER_C, lastWeek);
        insertProgress(r3, MySqlFixtures.CHAPTER_C, lastWeek);

        LocalDate expectedWeekStart = LocalDate.ofInstant(lastWeek, ZoneOffset.UTC)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        mockMvc.perform(get("/api/v1/metrics/retention")
                        .header("X-Lumin-Demo-Subject", OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.frozenDefinitionVersion").value(1))
                .andExpect(jsonPath("$.data.cohorts.length()").value(1))
                .andExpect(jsonPath("$.data.cohorts[0].weekStart").value(expectedWeekStart.toString()))
                .andExpect(jsonPath("$.data.cohorts[0].cohortSize").value(3))
                .andExpect(jsonPath("$.data.cohorts[0].retainedCount").value(1))
                .andExpect(jsonPath("$.data.cohorts[0].retentionRate").value(0.3333));
    }

    @Test
    void retentionWithNoRealActivityIsEmptyNotFabricated() throws Exception {
        // 只有合成流量触点 → 分群为空数组（无数据不编数字）
        insertProgress(insertProfile("sub-synth-only", "C1"), MySqlFixtures.CHAPTER_C,
                Instant.now().minus(Duration.ofDays(1)));
        mockMvc.perform(get("/api/v1/metrics/retention")
                        .header("X-Lumin-Demo-Subject", OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cohorts.length()").value(0));
    }

    @Test
    void supplyCountsReflectAuthoritativeTables() throws Exception {
        String removed = publishWork("供给之下架作");
        String curated = publishWork("供给之精选作");
        jdbc.update("UPDATE work SET business_state = 'REMOVED' WHERE id = ?", removed);
        // 运营精选在册一件（提案已发布作品）
        String cid = field(mockMvc.perform(post("/api/v1/curation-items")
                        .header("X-Lumin-Demo-Subject", OPERATOR)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + curated
                                + "\",\"exposureReason\":\"FEATURED\"}"))
                .andExpect(status().isOk()), "id");
        mockMvc.perform(post("/api/v1/curation-items/" + cid + "/reviews")
                        .header("X-Lumin-Demo-Subject", ADMIN)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isOk());

        // 真实读者档案两笔（其余夹具均为 sub- 合成口径）
        insertProfile("real-supply-1", "C2");
        insertProfile("real-supply-2", "C3");
        Integer syntheticProfiles = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_profile WHERE deleted_at IS NULL "
                        + "AND oidc_subject LIKE 'sub-%'", Integer.class);

        mockMvc.perform(get("/api/v1/metrics/supply")
                        .header("X-Lumin-Demo-Subject", OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.frozenDefinitionVersion").value(1))
                .andExpect(jsonPath("$.data.submissions").value(2))
                .andExpect(jsonPath("$.data.approved").value(2))
                .andExpect(jsonPath("$.data.rejected").value(0))
                .andExpect(jsonPath("$.data.takedownActive").value(1))
                .andExpect(jsonPath("$.data.featuredActive").value(1))
                .andExpect(jsonPath("$.data.realReaders").value(2))
                .andExpect(jsonPath("$.data.syntheticProfiles").value(syntheticProfiles));
    }
}
