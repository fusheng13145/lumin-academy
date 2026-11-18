package com.leyon.lumin.support;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UGC-005 收口：匿名化推进调度器集成测试（docs/13；03 §8）：
 * 调度器启用（lumin.closure.promotion.enabled=true）时——注销到期 → 匿名化；
 * HELD 的 ERASE 权利请求在保全解除后 → 续跑完成；未到期/保全未解除 → 不推进。
 * 调度间隔为小时级，测试直调 promoteOnce() 保证确定性（与 Outbox 调度同口径）。
 */
@SpringBootTest(properties = {"lumin.review.calibration-valid=true",
        "lumin.closure.promotion.enabled=true"})
@AutoConfigureMockMvc
class UGC005PromotionSchedulerIntegrationTest extends MySqlIntegrationBase {

    private static final String ADMIN = com.leyon.lumin.foundation.web.DemoAuthenticationFilter.ADMIN;
    private static final String AUTHOR_SUBJECT =
            com.leyon.lumin.foundation.web.DemoAuthenticationFilter.STUB_SUBJECT;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.leyon.lumin.identity.AnonymizationPromotionScheduler promotionScheduler;

    @org.junit.jupiter.api.BeforeEach
    void seedFixtures() {
        MySqlFixtures.insertStubAuthorProfile(jdbc);
        MySqlFixtures.insertConfigStubs(jdbc);
    }

    private static String uuidKey() {
        return UUID.randomUUID().toString();
    }

    private void requestClosure() throws Exception {
        mockMvc.perform(post("/api/v1/me/closure")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PENDING"));
    }

    private String createHold() throws Exception {
        return com.leyon.lumin.support.TestResponseSupport.field(mockMvc.perform(
                        post("/api/v1/legal-holds")
                                .header("X-Lumin-Demo-Subject", ADMIN)
                                .header("Idempotency-Key", uuidKey())
                                .contentType("application/json")
                                .content("{\"subjectType\":\"USER\",\"subjectId\":\""
                                        + AUTHOR_SUBJECT + "\",\"reason\":\"诉讼证据保全\"}"))
                .andExpect(status().isOk()), "id");
    }

    @Test
    void schedulerPromotesDueClosureAndHeldErase() throws Exception {
        // 注销（PENDING，6 个月后到期）+ HELD 的 ERASE 权利请求（同一主体）
        requestClosure();
        String holdId = createHold();
        mockMvc.perform(post("/api/v1/me/rights-requests")
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json")
                        .content("{\"kind\":\"ERASE\",\"reason\":\"保全期间擦除\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("HELD"));
        // 未到期 + 保全未解除 → 调度不推进
        promotionScheduler.promoteOnce();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM account_closure WHERE user_id = ?", String.class,
                AUTHOR_SUBJECT)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT state FROM rights_request WHERE hold_id = ?", String.class, holdId))
                .isEqualTo("HELD");
        assertThat(jdbc.queryForObject(
                "SELECT oidc_issuer FROM user_profile WHERE id = ?", String.class,
                AUTHOR_SUBJECT)).isNotEqualTo("urn:lumin:anonymized");
        // 释保全 → 调度推进：HELD ERASE 续跑 + 注销行仍待到期（未到 6 个月不匿名化）
        mockMvc.perform(post("/api/v1/legal-holds/" + holdId + "/releases")
                        .header("X-Lumin-Demo-Subject", ADMIN)
                        .header("Idempotency-Key", uuidKey())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isOk());
        promotionScheduler.promoteOnce();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM rights_request WHERE hold_id = ?", String.class, holdId))
                .isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject(
                "SELECT state FROM account_closure WHERE user_id = ?", String.class,
                AUTHOR_SUBJECT)).isEqualTo("PENDING");
        // 注销到期（模拟 6 个月已过）→ 调度推进匿名化
        jdbc.update("UPDATE account_closure SET anonymize_due_at = NOW(6) - INTERVAL 1 DAY "
                + "WHERE user_id = ?", AUTHOR_SUBJECT);
        promotionScheduler.promoteOnce();
        assertThat(jdbc.queryForObject(
                "SELECT state FROM account_closure WHERE user_id = ?", String.class,
                AUTHOR_SUBJECT)).isEqualTo("ANONYMIZED");
        assertThat(jdbc.queryForObject(
                "SELECT oidc_issuer FROM user_profile WHERE id = ?", String.class,
                AUTHOR_SUBJECT)).isEqualTo("urn:lumin:anonymized");
    }

    @Test
    void schedulerRunsCleanWhenNothingDue() throws Exception {
        // 无注销/无权利请求 → 调度空跑不报错
        promotionScheduler.promoteOnce();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM account_closure", Integer.class)).isZero();
    }
}
