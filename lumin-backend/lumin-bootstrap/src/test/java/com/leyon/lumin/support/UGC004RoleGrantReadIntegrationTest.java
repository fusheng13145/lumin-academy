package com.leyon.lumin.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UGC-004 角色授予读取链集成测试（05 §3.2 listRoleGrants/getRoleGrant）：
 * 列表按主体过滤（提案人/被授予人可见，无关主体不可见；ADMIN 全量）；
 * 详情返回原始请求与追加式审批记录（reviews[0]），越权/不存在统一 404。
 */
@SpringBootTest(properties = "lumin.review.calibration-valid=true")
@AutoConfigureMockMvc
class UGC004RoleGrantReadIntegrationTest extends MySqlIntegrationBase {

    private static final String OPERATOR = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.OPERATOR;
    private static final String ADMIN = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.ADMIN;
    private static final String OPERATOR_SUBJECT =
            com.leyon.lumin.foundation.web.DemoAuthenticationFilter.OPERATOR_SUBJECT;
    private static final String ADMIN_SUBJECT =
            com.leyon.lumin.foundation.web.DemoAuthenticationFilter.ADMIN_SUBJECT;
    private static final String TARGET_SUBJECT =
            com.leyon.lumin.foundation.web.DemoAuthenticationFilter.READER_C4_SUBJECT;
    private static final String OUTSIDER_SUBJECT =
            com.leyon.lumin.foundation.web.DemoAuthenticationFilter.REVIEWER_L1A_SUBJECT;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @BeforeEach
    void seedFixtures() {
        MySqlFixtures.insertConfigStubs(jdbc);
        MySqlFixtures.insertReaderProfiles(jdbc);
    }

    private String propose(String header, String target) throws Exception {
        return com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/role-grants")
                                .header("X-Lumin-Demo-Subject", header)
                                .contentType("application/json")
                                .content("{\"targetUserId\":\"" + target + "\",\"role\":\"READER\","
                                        + "\"scopeType\":\"GLOBAL\",\"reason\":\"读取链测试\"}"))
                .andExpect(status().isOk()), "id");
    }

    @Test
    void listFiltersBySubjectAndAdminSeesAll() throws Exception {
        String grantId = propose("OPERATOR", TARGET_SUBJECT);

        mockMvc.perform(get("/api/v1/role-grants")
                        .header("X-Lumin-Demo-Subject", "OPERATOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(grantId))
                .andExpect(jsonPath("$.data.items[0].request.targetUserId").value(TARGET_SUBJECT))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());

        // 被授予人可见；无关审核员不可见（先过滤再分页）
        mockMvc.perform(get("/api/v1/role-grants")
                        .header("X-Lumin-Demo-Subject", "READER_C4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
        mockMvc.perform(get("/api/v1/role-grants")
                        .header("X-Lumin-Demo-Subject", "REVIEWER_L1A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));

        mockMvc.perform(get("/api/v1/role-grants")
                        .header("X-Lumin-Demo-Subject", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    @Test
    void detailContainsReviewHistoryAndHidesFromOutsider() throws Exception {
        String grantId = propose("OPERATOR", TARGET_SUBJECT);
        mockMvc.perform(post("/api/v1/role-grants/" + grantId + "/reviews")
                        .header("X-Lumin-Demo-Subject", "ADMIN")
                        .contentType("application/json")
                        .content("{\"action\":\"APPROVE\",\"reason\":\"材料齐全\","
                                + "\"evidenceRefs\":[\"case:UGC-004\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("APPROVED"));

        mockMvc.perform(get("/api/v1/role-grants/" + grantId)
                        .header("X-Lumin-Demo-Subject", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdBy").value(OPERATOR_SUBJECT))
                .andExpect(jsonPath("$.data.state").value("APPROVED"))
                .andExpect(jsonPath("$.data.reviews.length()").value(1))
                .andExpect(jsonPath("$.data.reviews[0].actorId").value(ADMIN_SUBJECT))
                .andExpect(jsonPath("$.data.reviews[0].action").value("APPROVE"))
                .andExpect(jsonPath("$.data.reviews[0].reason").value("材料齐全"))
                .andExpect(jsonPath("$.data.reviews[0].evidenceRefs[0]").value("case:UGC-004"));

        // 越权/不存在统一 404（不泄露是否存在）
        mockMvc.perform(get("/api/v1/role-grants/" + grantId)
                        .header("X-Lumin-Demo-Subject", "REVIEWER_L1A"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("B2004"));
        mockMvc.perform(get("/api/v1/role-grants/" + UUID.randomUUID())
                        .header("X-Lumin-Demo-Subject", "ADMIN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("B2004"));
    }
}
