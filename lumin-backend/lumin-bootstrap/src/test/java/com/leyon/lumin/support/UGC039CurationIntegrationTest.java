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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UGC-039 运营精选集成测试（docs/13 M1；V18）：提案（OPERATOR）→独立审批
 * （ADMIN）→ACTIVE 曝光（exposureReason + intervention=true 干预显式）→
 * 撤位消失；角色越权 403；可见性与 /works 同源（PG_16 带闸、下架不出、
 * 不绕过适龄）；同规则版本同作品重复提案 409；分类历史走版本链。
 */
@SpringBootTest(properties = "lumin.review.calibration-valid=true")
@AutoConfigureMockMvc
class UGC039CurationIntegrationTest extends MySqlIntegrationBase {

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
    private static final String READER_C1 = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C1;

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

    /** 发布作品（G/PG_16 可选）。 */
    private String publishWork(String title, String grade) throws Exception {
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
                                + "\"suggestedDecision\":\"PASS\",\"grade\":\"" + grade
                                + "\",\"confidence\":0.99,\"evidenceRefs\":[\"ev:1\"],"
                                + "\"inputTokens\":100,\"outputTokens\":10,\"durationMs\":1000}"))
                .andExpect(status().isOk());
        dispatcher.deliverPending();
        return workId;
    }

    private String propose(String operator, String workId, String reason) throws Exception {
        return field(mockMvc.perform(post("/api/v1/curation-items")
                        .header("X-Lumin-Demo-Subject", operator)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"exposureReason\":\"" + reason
                                + "\"}"))
                .andExpect(status().isOk()), "id");
    }

    private ResultActions approve(String admin, String id) throws Exception {
        return mockMvc.perform(post("/api/v1/curation-items/" + id + "/reviews")
                .header("X-Lumin-Demo-Subject", admin)
                .header("Idempotency-Key", uuidKey())
                .contentType("application/json")
                .content("{\"decision\":\"APPROVE\"}"));
    }

    @Test
    void readerAndAdminCannotPropose() throws Exception {
        String workId = publishWork("精选目标", "G");
        mockMvc.perform(post("/api/v1/curation-items")
                        .header("X-Lumin-Demo-Subject", READER_C3)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"exposureReason\":\"FEATURED\"}"))
                .andExpect(status().isForbidden());
        // 提案须 OPERATOR（审批须 ADMIN——角色互斥结构性排除自审）
        mockMvc.perform(post("/api/v1/curation-items")
                        .header("X-Lumin-Demo-Subject", ADMIN)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"exposureReason\":\"FEATURED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void proposeReviewActivateFlow() throws Exception {
        String workId = publishWork("精选之作", "G");
        String id = propose(OPERATOR, workId, "FEATURED");
        // 审批前不在精选位
        mockMvc.perform(get("/api/v1/works/featured")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        approve(ADMIN, id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ACTIVE"));

        // 读者精选位：干预标识显式
        mockMvc.perform(get("/api/v1/works/featured")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].workId").value(workId))
                .andExpect(jsonPath("$.data[0].exposureReason").value("FEATURED"))
                .andExpect(jsonPath("$.data[0].intervention").value(true))
                .andExpect(jsonPath("$.data[0].title").value("精选之作"));
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM curation_item WHERE id = ? AND state = 'ACTIVE'",
                Integer.class, id);
        assertThat(rows).isEqualTo(1);
    }

    @Test
    void operatorCannotApproveAndRejectPathWorks() throws Exception {
        String workId = publishWork("驳回目标", "G");
        String id = propose(OPERATOR, workId, "EDITOR_PICK");
        // OPERATOR 无权审批
        mockMvc.perform(post("/api/v1/curation-items/" + id + "/reviews")
                        .header("X-Lumin-Demo-Subject", OPERATOR)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isForbidden());
        // ADMIN 驳回 → 不曝光
        mockMvc.perform(post("/api/v1/curation-items/" + id + "/reviews")
                        .header("X-Lumin-Demo-Subject", ADMIN)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"decision\":\"REJECT\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("REJECTED"));
        mockMvc.perform(get("/api/v1/works/featured")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        // 已裁决（REJECTED）再审批 → 409 B2001
        approve(ADMIN, id).andExpect(status().isConflict());
    }

    @Test
    void duplicateProposalSameRuleWorkIsConflict() throws Exception {
        String workId = publishWork("重复提案目标", "G");
        propose(OPERATOR, workId, "FEATURED");
        mockMvc.perform(post("/api/v1/curation-items")
                        .header("X-Lumin-Demo-Subject", OPERATOR)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"workId\":\"" + workId + "\",\"exposureReason\":\"FEATURED\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void featuredRespectsAgeBandGate() throws Exception {
        String pg16 = publishWork("深夜精选", "PG_16");
        String g = publishWork("日间精选", "G");
        String pg16Id = propose(OPERATOR, pg16, "FEATURED");
        String gId = propose(OPERATOR, g, "FEATURED");
        approve(ADMIN, pg16Id).andExpect(status().isOk());
        approve(ADMIN, gId).andExpect(status().isOk());

        // C3 可见两件；C1 仅 G——不绕过适龄
        mockMvc.perform(get("/api/v1/works/featured")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        mockMvc.perform(get("/api/v1/works/featured")
                        .header("X-Lumin-Demo-Subject", READER_C1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("日间精选"));
    }

    @Test
    void removedWorkDropsFromFeatured() throws Exception {
        String workId = publishWork("下架精选", "G");
        String id = propose(OPERATOR, workId, "FEATURED");
        approve(ADMIN, id).andExpect(status().isOk());
        jdbc.update("UPDATE work SET business_state = 'REMOVED' WHERE id = ?", workId);

        mockMvc.perform(get("/api/v1/works/featured")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void retireRemovesFromFeatured() throws Exception {
        String workId = publishWork("撤位精选", "G");
        String id = propose(OPERATOR, workId, "FEATURED");
        approve(ADMIN, id).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/curation-items/" + id)
                        .header("X-Lumin-Demo-Subject", ADMIN)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("RETIRED"));

        mockMvc.perform(get("/api/v1/works/featured")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        // 已 RETIRED 再撤位 → 409
        mockMvc.perform(delete("/api/v1/curation-items/" + id)
                        .header("X-Lumin-Demo-Subject", ADMIN)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void categoryHistoryIsVersionChainAndRoleGated() throws Exception {
        String workId = publishWork("分类历史之作", "G");
        // 读者无权
        mockMvc.perform(get("/api/v1/works/" + workId + "/category-history")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isForbidden());
        // OPERATOR 可读：发布版本链即分类历史
        mockMvc.perform(get("/api/v1/works/" + workId + "/category-history")
                        .header("X-Lumin-Demo-Subject", OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].categoryId")
                        .value(MySqlFixtures.CATEGORY_LITERATURE))
                .andExpect(jsonPath("$.data[0].versionState").value("APPROVED"));
    }

    @Test
    void curationListFiltersByStateAndGatesRoles() throws Exception {
        String workA = publishWork("列表甲", "G");
        String workB = publishWork("列表乙", "G");
        String idA = propose(OPERATOR, workA, "FEATURED");
        String idB = propose(OPERATOR, workB, "EDITOR_PICK");
        approve(ADMIN, idA).andExpect(status().isOk());

        // 读者无权
        mockMvc.perform(get("/api/v1/curation-items")
                        .header("X-Lumin-Demo-Subject", READER_C3))
                .andExpect(status().isForbidden());
        // 非法 state → 400
        mockMvc.perform(get("/api/v1/curation-items")
                        .param("state", "WEIRD")
                        .header("X-Lumin-Demo-Subject", OPERATOR))
                .andExpect(status().isBadRequest());
        // 全量 2 条，提案时间倒序（乙新在前）
        mockMvc.perform(get("/api/v1/curation-items")
                        .header("X-Lumin-Demo-Subject", OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].workId").value(workB))
                .andExpect(jsonPath("$.data[0].state").value("PENDING"))
                .andExpect(jsonPath("$.data[1].workId").value(workA))
                .andExpect(jsonPath("$.data[1].state").value("ACTIVE"));
        // state=ACTIVE 过滤 → 仅甲
        mockMvc.perform(get("/api/v1/curation-items")
                        .param("state", "ACTIVE")
                        .header("X-Lumin-Demo-Subject", OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].workId").value(workA));
        // state=PENDING 过滤 → 仅乙
        mockMvc.perform(get("/api/v1/curation-items")
                        .param("state", "PENDING")
                        .header("X-Lumin-Demo-Subject", OPERATOR))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].workId").value(workB));
    }
}
