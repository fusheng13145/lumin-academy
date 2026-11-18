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
 * UGC-006 数据主体权利请求集成测试（docs/13；04 §5.2 rights_request；
 * 03 §2.4 OPEN→PROCESSING→COMPLETED/HELD；6.9）：
 * ACCESS/EXPORT/CORRECT 自助闭环（EXPORT 24h 到期、result_ref 加密留痕不回显、
 * 更正昵称生效）；ERASE 受法律保全闸门（ACTIVE 保全 → HELD、解除后 retryHeld
 * 续跑匿名化完成）；仅本人可见（他人 404）；审计事实不删。
 */
@SpringBootTest(properties = "lumin.review.calibration-valid=true")
@AutoConfigureMockMvc
class UGC006RightsRequestIntegrationTest extends MySqlIntegrationBase {

    private static final String ADMIN = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.ADMIN;
    private static final String AUTHOR_SUBJECT =
            com.leyon.lumin.foundation.web.DemoAuthenticationFilter.STUB_SUBJECT;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.leyon.lumin.identity.service.RightsRequestService rightsService;

    @org.junit.jupiter.api.BeforeEach
    void seedFixtures() {
        MySqlFixtures.insertStubAuthorProfile(jdbc);
    }

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    @Test
    void accessExportCorrectSelfServiceCloseInOneStep() throws Exception {
        // ACCESS：本人资料快照（result_ref 加密留痕，视图 hasResult=true 不回显内容）
        mockMvc.perform(post("/api/v1/me/rights-requests")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"kind\":\"ACCESS\",\"reason\":\"查询本人数据\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("ACCESS"))
                .andExpect(jsonPath("$.data.state").value("COMPLETED"))
                .andExpect(jsonPath("$.data.hasResult").value(true));
        // EXPORT：24h 到期
        MvcResult exportResult = mockMvc.perform(post("/api/v1/me/rights-requests")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"kind\":\"EXPORT\",\"reason\":\"导出本人数据\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kind").value("EXPORT"))
                .andExpect(jsonPath("$.data.state").value("COMPLETED"))
                .andReturn();
        String exportId = com.leyon.lumin.support.TestResponseSupport.field(exportResult, "id");
        assertThat(jdbc.queryForObject(
                "SELECT TIMESTAMPDIFF(HOUR, created_at, due_at) FROM rights_request "
                        + "WHERE id = ?", Integer.class, exportId)).isEqualTo(24);
        // CORRECT：更正公开昵称生效
        mockMvc.perform(post("/api/v1/me/rights-requests")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"kind\":\"CORRECT\",\"reason\":\"更正昵称\","
                                + "\"correctionNickname\":\"更正后的作者\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("COMPLETED"));
        assertThat(jdbc.queryForObject(
                "SELECT nickname FROM user_profile WHERE id = ?", String.class,
                AUTHOR_SUBJECT)).isEqualTo("更正后的作者");
        // 仅本人可见：他人 GET → 404
        mockMvc.perform(get("/api/v1/me/rights-requests/"
                        + com.leyon.lumin.support.TestResponseSupport.field(
                                mockMvc.perform(get("/api/v1/me/rights-requests"))
                                        .andExpect(status().isOk())
                                        .andReturn(), "id"))
                        .header("X-Lumin-Demo-Subject",
                                com.leyon.lumin.foundation.web.DemoAuthenticationFilter.AUDITOR))
                .andExpect(status().isNotFound());
    }

    @Test
    void eraseIsGatedByLegalHoldAndContinuesAfterRelease() throws Exception {
        // ERASE 无保全 → 匿名化完成
        mockMvc.perform(post("/api/v1/me/rights-requests")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"kind\":\"ERASE\",\"reason\":\"擦除个人标识关联\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("COMPLETED"));
        assertThat(jdbc.queryForObject(
                "SELECT oidc_issuer FROM user_profile WHERE id = ?", String.class,
                AUTHOR_SUBJECT)).isEqualTo("urn:lumin:anonymized");
        // 审计事实不删
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_record WHERE resource_type = 'rights_request' "
                        + "AND action = 'rights_request.erase.completed'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void eraseWithLegalHoldIsHeldThenCompletesAfterRelease() throws Exception {
        // 先立 USER 保全 → ERASE → HELD（匿名化暂缓）
        String holdId = com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/legal-holds")
                                .header("X-Lumin-Demo-Subject", ADMIN)
                                .header("Idempotency-Key", uuidKey())
                                .contentType("application/json")
                                .content("{\"subjectType\":\"USER\",\"subjectId\":\""
                                        + AUTHOR_SUBJECT + "\",\"reason\":\"诉讼证据保全\"}"))
                .andExpect(status().isOk()), "id");
        mockMvc.perform(post("/api/v1/me/rights-requests")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"kind\":\"ERASE\",\"reason\":\"保全期间擦除\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("HELD"))
                .andExpect(jsonPath("$.data.holdId").value(holdId));
        assertThat(jdbc.queryForObject(
                "SELECT oidc_issuer FROM user_profile WHERE id = ?", String.class,
                AUTHOR_SUBJECT)).isNotEqualTo("urn:lumin:anonymized");
        // 释保全 → retryHeld 续跑 → 匿名化完成
        mockMvc.perform(post("/api/v1/legal-holds/" + holdId + "/releases")
                        .header("X-Lumin-Demo-Subject", ADMIN)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        assertThat(rightsService.retryHeld(
                java.time.LocalDateTime.now(java.time.ZoneOffset.UTC))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM rights_request WHERE hold_id = ?", String.class, holdId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "SELECT oidc_issuer FROM user_profile WHERE id = ?", String.class,
                AUTHOR_SUBJECT)).isEqualTo("urn:lumin:anonymized");
    }
}
