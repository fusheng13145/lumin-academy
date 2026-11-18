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
 * T27 读者链矩阵一致性（07 T27）：G/PG_12/PG_16/A × C0–C4 各 1 作品与章节，
 * 列表 / 详情 / 目录 / 正文 四入口对每一组合判定一致（期望值取自 AgePolicy
 * 纯函数——列表 SQL 等价过滤与逐条三态裁决同源）；A 级 P0 默认关闭
 * （adultContentEnabled=false 契约口径），全部入口 404 且列表不泄露隐藏计数。
 */
@SpringBootTest(properties = "lumin.review.calibration-valid=true")
@AutoConfigureMockMvc
class T27ReaderChainMatrixIntegrationTest extends MySqlIntegrationBase {

    /** 嵌套配置：抽检闸恒未命中——AUTO 链路确定性验证。 */
    @org.springframework.boot.test.context.TestConfiguration
    static class NeverSampledGate {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        com.leyon.lumin.moderation.service.AutoSampleGate neverSampled() {
            return caseId -> false;
        }
    }

    private static final String[] GRADES = {"G", "PG_12", "PG_16", "A"};
    private static final String[] BANDS = {"C0", "C1", "C2", "C3", "C4"};

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.leyon.lumin.foundation.event.OutboxDispatcher dispatcher;

    @BeforeEach
    void seedFixtures() {
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

    /** 提交 + 机审 LOW PASS（grade 由报告携带）+ 投递 → 发布；返回 workId。 */
    private String publishWork(String grade) throws Exception {
        String workId = field(mockMvc.perform(
                        post("/api/v1/works").header("Idempotency-Key", uuidKey())
                                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String draftId = field(mockMvc.perform(
                        post("/api/v1/drafts").header("Idempotency-Key", uuidKey())
                                .contentType("application/json")
                                .content("{\"workId\":\"" + workId + "\",\"title\":\"作品-" + grade
                                        + "\",\"synopsis\":\"简介\",\"categoryId\":\""
                                        + MySqlFixtures.CATEGORY_LITERATURE + "\",\"tags\":[]}"))
                .andExpect(status().isOk()), "id");
        submitAndReport(draftId, grade);
        return workId;
    }

    /** 父作品已发布前提下发布章节；返回 chapterId。 */
    private String publishChapter(String workId, String grade) throws Exception {
        long lock = jdbc.queryForObject(
                "SELECT lock_version FROM work WHERE id = ?", Long.class, workId);
        String chapterId = field(mockMvc.perform(
                        post("/api/v1/works/" + workId + "/chapters")
                                .header("Idempotency-Key", uuidKey())
                                .header("If-Match", "\"" + lock + "\"")
                                .contentType("application/json").content("{}"))
                .andExpect(status().isOk()), "id");
        String draftId = field(mockMvc.perform(
                        post("/api/v1/drafts").header("Idempotency-Key", uuidKey())
                                .contentType("application/json")
                                .content("{\"workId\":\"" + workId + "\",\"chapterId\":\"" + chapterId
                                        + "\",\"title\":\"章节-" + grade + "\",\"body\":\"正文段落\"}"))
                .andExpect(status().isOk()), "id");
        submitAndReport(draftId, grade);
        return chapterId;
    }

    private void submitAndReport(String draftId, String grade) throws Exception {
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
                "SELECT version_id FROM publication_workflow WHERE id = ?", String.class, workflowId);
        String materialSha = jdbc.queryForObject(
                "SELECT material_sha256 FROM content_version WHERE id = ?", String.class, versionId);
        mockMvc.perform(post("/internal/v1/machine-reports")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content(("{" + "\"caseId\":\"%s\",\"workflowId\":\"%s\",\"versionId\":\"%s\","
                                + "\"policyVersion\":1,\"materialSha256\":\"%s\","
                                + "\"modelVersion\":\"guard-v1\",\"risk\":\"LOW\",\"riskFlags\":[],"
                                + "\"suggestedDecision\":\"PASS\",\"grade\":\"%s\","
                                + "\"confidence\":0.99,\"evidenceRefs\":[\"ev:1\"],"
                                + "\"inputTokens\":100,\"outputTokens\":10,\"durationMs\":1000}")
                                .formatted(caseId, workflowId, versionId, materialSha, grade)))
                .andExpect(status().isOk());
        dispatcher.deliverPending();
    }

    private static String readerHeader(String band) {
        return "READER_" + band;
    }

    @Test
    void readerChainMatrixIsConsistentAcrossFourEntries() throws Exception {
        String[] workIds = new String[GRADES.length];
        String[] chapterIds = new String[GRADES.length];
        for (int i = 0; i < GRADES.length; i++) {
            workIds[i] = publishWork(GRADES[i]);
            chapterIds[i] = publishChapter(workIds[i], GRADES[i]);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM publication_pointer WHERE target_type = 'WORK' "
                            + "AND target_id = ?", Integer.class, workIds[i])).isEqualTo(1);
        }

        for (String band : BANDS) {
            // 期望值取自 AgePolicy 纯函数（列表过滤与三态裁决同源口径）
            int expected = 0;
            for (String grade : GRADES) {
                if (com.leyon.lumin.identity.domain.AgePolicy.canReadPublic(
                        com.leyon.lumin.foundation.values.AgeBand.valueOf(band),
                        com.leyon.lumin.foundation.values.Grade.valueOf(grade))) {
                    expected++;
                }
            }
            MvcResult listResult = mockMvc.perform(get("/api/v1/works")
                            .header("X-Lumin-Demo-Subject", readerHeader(band)))
                    .andExpect(status().isOk())
                    // 列表信封仅含 data 数组：不泄露被过滤作品的计数（T27 口径）
                    .andExpect(jsonPath("$.data.length()").value(expected))
                    .andReturn();
            String listJson = listResult.getResponse().getContentAsString(StandardCharsets.UTF_8);
            for (int i = 0; i < GRADES.length; i++) {
                boolean readable = com.leyon.lumin.identity.domain.AgePolicy.canReadPublic(
                        com.leyon.lumin.foundation.values.AgeBand.valueOf(band),
                        com.leyon.lumin.foundation.values.Grade.valueOf(GRADES[i]));
                assertThat(listJson.contains(workIds[i]))
                        .as("列表含 %s 作品（band=%s, readable=%s）", GRADES[i], band, readable)
                        .isEqualTo(readable);
                // 详情
                if (readable) {
                    mockMvc.perform(get("/api/v1/works/" + workIds[i])
                                    .header("X-Lumin-Demo-Subject", readerHeader(band)))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.data.grade").value(GRADES[i]));
                    // 目录
                    mockMvc.perform(get("/api/v1/works/" + workIds[i] + "/chapters")
                                    .header("X-Lumin-Demo-Subject", readerHeader(band)))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.data.length()").value(1));
                    // 正文
                    mockMvc.perform(get("/api/v1/chapters/" + chapterIds[i] + "/content")
                                    .header("X-Lumin-Demo-Subject", readerHeader(band)))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.data.grade").value(GRADES[i]));
                } else {
                    // 未授权统一 404/B2004：详情、目录、正文三入口一致不泄露存在性
                    mockMvc.perform(get("/api/v1/works/" + workIds[i])
                                    .header("X-Lumin-Demo-Subject", readerHeader(band)))
                            .andExpect(status().isNotFound())
                            .andExpect(jsonPath("$.code").value("B2004"));
                    mockMvc.perform(get("/api/v1/works/" + workIds[i] + "/chapters")
                                    .header("X-Lumin-Demo-Subject", readerHeader(band)))
                            .andExpect(status().isNotFound())
                            .andExpect(jsonPath("$.code").value("B2004"));
                    mockMvc.perform(get("/api/v1/chapters/" + chapterIds[i] + "/content")
                                    .header("X-Lumin-Demo-Subject", readerHeader(band)))
                            .andExpect(status().isNotFound())
                            .andExpect(jsonPath("$.code").value("B2004"));
                }
            }
        }
        // A 级默认关闭：任何年龄带（含 C3 成年）经公开链路均不可读
        String aWork = workIds[GRADES.length - 1];
        for (String band : BANDS) {
            assertThat(jdbc.queryForObject(
                    "SELECT grade FROM content_version WHERE work_id = ? LIMIT 1",
                    String.class, aWork)).isEqualTo("A");
            mockMvc.perform(get("/api/v1/works/" + aWork)
                            .header("X-Lumin-Demo-Subject", readerHeader(band)))
                    .andExpect(status().isNotFound());
        }
    }
}
