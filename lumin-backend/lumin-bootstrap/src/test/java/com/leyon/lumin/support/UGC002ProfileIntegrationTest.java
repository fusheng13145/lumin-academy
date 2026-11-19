package com.leyon.lumin.support;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.identity.service.ProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * UGC-002/UGC-003/UGC-007 身份建档第一切片（05 §3.1 createProfile / getMe）：
 * 生日密文落库、声明层由 AgePolicy 计算、未核验有效层从严 C4、本人档案 ETag；
 * 幂等回放/同键异载/重复建档；&lt;14 在 guardian_consent 真链路前拒绝。
 */
@SpringBootTest
@AutoConfigureMockMvc
class UGC002ProfileIntegrationTest extends MySqlIntegrationBase {

    private static final String NEWCOMER =
            com.leyon.lumin.foundation.web.DemoAuthenticationFilter.NEWCOMER;
    private static final String NEWCOMER_SUBJECT =
            com.leyon.lumin.foundation.web.DemoAuthenticationFilter.NEWCOMER_SUBJECT;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ProfileService profileService;

    private static String body(String nickname, String birthDate) {
        return "{\"nickname\":\"" + nickname + "\",\"birthDate\":\"" + birthDate + "\"}";
    }

    @Test
    void createProfileStoresCipherStrictC4ThenMe() throws Exception {
        String key = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/profiles")
                        .header("X-Lumin-Demo-Subject", NEWCOMER)
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(body("新读者", "2000-01-01")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(NEWCOMER_SUBJECT))
                .andExpect(jsonPath("$.data.resourceType").value("PROFILE"))
                .andExpect(jsonPath("$.data.state").value("ACTIVE"))
                .andExpect(jsonPath("$.data.revision").value(1));

        // 出生日期只以密文落库；声明 C3、有效 C4（未核验不升级）
        byte[] cipher = jdbc.queryForObject(
                "SELECT birth_date_cipher FROM user_profile WHERE id = ?",
                byte[].class, NEWCOMER_SUBJECT);
        assertThat(cipher).isNotNull();
        assertThat(cipher.length).isGreaterThan(16);
        assertThat(new String(cipher, StandardCharsets.UTF_8)).doesNotContain("2000-01-01");
        assertThat(jdbc.queryForObject(
                "SELECT declared_age_band FROM user_profile WHERE id = ?",
                String.class, NEWCOMER_SUBJECT)).isEqualTo("C3");
        assertThat(jdbc.queryForObject(
                "SELECT effective_age_band FROM user_profile WHERE id = ?",
                String.class, NEWCOMER_SUBJECT)).isEqualTo("C4");
        assertThat(jdbc.queryForObject(
                "SELECT verified_age_band FROM user_profile WHERE id = ?",
                String.class, NEWCOMER_SUBJECT)).isNull();

        // 本人档案：角色来自会话上下文（尚无 role_assignment），ETag=revision
        mockMvc.perform(get("/api/v1/me")
                        .header("X-Lumin-Demo-Subject", NEWCOMER))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.data.id").value(NEWCOMER_SUBJECT))
                .andExpect(jsonPath("$.data.nickname").value("新读者"))
                .andExpect(jsonPath("$.data.effectiveAgeBand").value("C4"))
                .andExpect(jsonPath("$.data.roles[0]").value("READER"))
                .andExpect(jsonPath("$.data.accountStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data.revision").value(1));

        // 同键同载荷 → 回放 200；同键异载荷 → 409/B2003
        mockMvc.perform(post("/api/v1/profiles")
                        .header("X-Lumin-Demo-Subject", NEWCOMER)
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(body("新读者", "2000-01-01")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/profiles")
                        .header("X-Lumin-Demo-Subject", NEWCOMER)
                        .header("Idempotency-Key", key)
                        .contentType("application/json")
                        .content(body("另一个昵称", "2000-01-01")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("B2003"));

        // 新键重复建档 → 409/B2001（不覆盖既有档案）
        mockMvc.perform(post("/api/v1/profiles")
                        .header("X-Lumin-Demo-Subject", NEWCOMER)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(body("再次建档", "2000-01-01")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("B2001"));
    }

    @Test
    void meWithoutProfileIs404() throws Exception {
        mockMvc.perform(get("/api/v1/me")
                        .header("X-Lumin-Demo-Subject", NEWCOMER))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("B2004"));
    }

    @Test
    void minorRegistrationIsRejectedUntilGuardianConsentIsVerifiable() {
        LuminBusinessException rejected = assertThrows(LuminBusinessException.class,
                () -> profileService.createProfile(UUID.randomUUID().toString(),
                        "未成年读者", "2015-01-01", "proof-ref", LocalDate.of(2026, 11, 19),
                        LocalDateTime.of(2026, 11, 19, 0, 0)));
        assertEquals(ErrorCode.A2002, rejected.code());
    }
}
