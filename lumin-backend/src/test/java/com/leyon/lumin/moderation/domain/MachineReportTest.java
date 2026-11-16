package com.leyon.lumin.moderation.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.Grade;
import com.leyon.lumin.foundation.values.RiskFlag;
import com.leyon.lumin.foundation.values.RiskLevel;
import com.leyon.lumin.foundation.values.ReviewDecision;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 机审报告契约校验（T18：未知/越界/错误枚举/错摘要/错绑定全拒绝，无 APPROVED、无指针）。 */
class MachineReportTest {

    private static final UUID CASE_ID =
            UUID.fromString("2a7c9e1b-4d3f-4c2a-8b5e-6f7a8b9c0d1e");
    private static final UUID WORKFLOW_ID =
            UUID.fromString("3b8d0f2c-5e4a-4d3b-9c6f-7a8b9c0d1e2f");
    private static final UUID VERSION_ID =
            UUID.fromString("4c9e1a3d-6f5b-4e4c-8d7a-8b9c0d1e2f3a");
    private static final String MATERIAL_SHA256 =
            "0123456789abcdef".repeat(4);

    private static MachineReport validReport() {
        return new MachineReport(CASE_ID, WORKFLOW_ID, VERSION_ID, 1, MATERIAL_SHA256,
                "lumin-guard-v1", RiskLevel.LOW, Set.of(),
                ReviewDecision.PASS, Grade.G, new BigDecimal("0.99"),
                List.of("evidence:001"), 1000, 200, 5000);
    }

    @Test
    void validReportPasses() {
        assertEquals(ErrorCode.OK, validReport().validate());
        // 全部必要字段齐备的边界值：token 与时长上限
        assertEquals(ErrorCode.OK, new MachineReport(CASE_ID, WORKFLOW_ID, VERSION_ID, 1,
                MATERIAL_SHA256, "m", RiskLevel.LOW, Set.of(RiskFlag.PRIVACY, RiskFlag.COPYRIGHT),
                ReviewDecision.LABEL, Grade.PG_12, new BigDecimal("1"),
                List.of("a:1", "b-2", "c_3"), 40000, 2000, 120000).validate());
    }

    @Test
    void missingOrMalformedIdentifiersAreRejected() {
        assertEquals(ErrorCode.P1001, new MachineReport(null, WORKFLOW_ID, VERSION_ID, 1,
                MATERIAL_SHA256, "m", RiskLevel.LOW, Set.of(), ReviewDecision.PASS, Grade.G,
                new BigDecimal("0.99"), List.of("e:1"), 1, 1, 1).validate());
        assertEquals(ErrorCode.P1001, new MachineReport(CASE_ID, null, VERSION_ID, 1,
                MATERIAL_SHA256, "m", RiskLevel.LOW, Set.of(), ReviewDecision.PASS, Grade.G,
                new BigDecimal("0.99"), List.of("e:1"), 1, 1, 1).validate());
        assertEquals(ErrorCode.P1001, new MachineReport(CASE_ID, WORKFLOW_ID, null, 0,
                MATERIAL_SHA256, "m", RiskLevel.LOW, Set.of(), ReviewDecision.PASS, Grade.G,
                new BigDecimal("0.99"), List.of("e:1"), 1, 1, 1).validate());
        // 错 materialSha256（大写/短串）
        assertEquals(ErrorCode.P1001, new MachineReport(CASE_ID, WORKFLOW_ID, VERSION_ID, 1,
                MATERIAL_SHA256.toUpperCase(), "m", RiskLevel.LOW, Set.of(),
                ReviewDecision.PASS, Grade.G, new BigDecimal("0.99"), List.of("e:1"),
                1, 1, 1).validate());
        assertEquals(ErrorCode.P1001, new MachineReport(CASE_ID, WORKFLOW_ID, VERSION_ID, 1,
                "abc", "m", RiskLevel.LOW, Set.of(), ReviewDecision.PASS, Grade.G,
                new BigDecimal("0.99"), List.of("e:1"), 1, 1, 1).validate());
    }

    @Test
    void confidenceOutOfOrBeyondPrecisionIsRejected() {
        assertEquals(ErrorCode.P1001, withConfidence("-0.1").validate());   // T18: -0.1
        assertEquals(ErrorCode.P1001, withConfidence("1.1").validate());    // T18: 1.1
        assertEquals(ErrorCode.P1001, withConfidence("0.12345").validate()); // NUMERIC(5,4)
        assertEquals(ErrorCode.P1001, withConfidence(null).validate());
        assertEquals(ErrorCode.OK, withConfidence("0").validate());
        assertEquals(ErrorCode.OK, withConfidence("1").validate());
        assertEquals(ErrorCode.OK, withConfidence("0.9999").validate());
    }

    private static MachineReport withConfidence(String value) {
        return new MachineReport(CASE_ID, WORKFLOW_ID, VERSION_ID, 1, MATERIAL_SHA256,
                "m", RiskLevel.LOW, Set.of(), ReviewDecision.PASS, Grade.G,
                value == null ? null : new BigDecimal(value),
                List.of("e:1"), 1, 1, 1);
    }

    @Test
    void gradePairingViolationsAreRejected() {
        // T18：PASS 缺 grade、REVISE 带 grade 均 400
        assertEquals(ErrorCode.P1001, new MachineReport(CASE_ID, WORKFLOW_ID, VERSION_ID, 1,
                MATERIAL_SHA256, "m", RiskLevel.LOW, Set.of(), ReviewDecision.PASS, null,
                new BigDecimal("0.99"), List.of("e:1"), 1, 1, 1).validate());
        assertEquals(ErrorCode.P1001, new MachineReport(CASE_ID, WORKFLOW_ID, VERSION_ID, 1,
                MATERIAL_SHA256, "m", RiskLevel.LOW, Set.of(), ReviewDecision.REVISE, Grade.G,
                new BigDecimal("0.99"), List.of("e:1"), 1, 1, 1).validate());
    }

    @Test
    void riskFlagsAndEvidenceRefsBounds() {
        // 风险标记全集 5 个、Set 天然去重：size>5 按构造不可达，此处验证全集与去重合法
        assertEquals(ErrorCode.OK, new MachineReport(CASE_ID, WORKFLOW_ID, VERSION_ID, 1,
                MATERIAL_SHA256, "m", RiskLevel.LOW,
                Set.of(RiskFlag.MINOR, RiskFlag.ILLEGAL, RiskFlag.MONEY_DISPUTE,
                        RiskFlag.PRIVACY, RiskFlag.COPYRIGHT),
                ReviewDecision.PASS, Grade.G, new BigDecimal("0.99"), List.of("e:1"),
                1, 1, 1).validate());
        assertEquals(ErrorCode.P1001, withEvidenceRefs(List.of()).validate());
        assertEquals(ErrorCode.P1001, withEvidenceRefs(
                java.util.stream.IntStream.rangeClosed(1, 11)
                        .mapToObj(i -> "e:" + i).toList()).validate());
        assertEquals(ErrorCode.P1001, withEvidenceRefs(List.of("e:1", "e:1")).validate());
        assertEquals(ErrorCode.P1001, withEvidenceRefs(List.of("含中文")).validate());
        assertEquals(ErrorCode.P1001, withEvidenceRefs(List.of("")).validate());
        assertEquals(ErrorCode.P1001, withEvidenceRefs(List.of("x".repeat(201))).validate());
        assertEquals(ErrorCode.OK, withEvidenceRefs(List.of("x".repeat(200))).validate());
    }

    private static MachineReport withEvidenceRefs(List<String> refs) {
        return new MachineReport(CASE_ID, WORKFLOW_ID, VERSION_ID, 1, MATERIAL_SHA256,
                "m", RiskLevel.LOW, Set.of(), ReviewDecision.PASS, Grade.G,
                new BigDecimal("0.99"), refs, 1, 1, 1);
    }

    @Test
    void budgetFieldsBounds() {
        assertEquals(ErrorCode.P1001, withBudget(-1, 1, 1).validate());       // T18: 负 token
        assertEquals(ErrorCode.P1001, withBudget(40001, 1, 1).validate());
        assertEquals(ErrorCode.P1001, withBudget(1, -1, 1).validate());
        assertEquals(ErrorCode.P1001, withBudget(1, 2001, 1).validate());
        assertEquals(ErrorCode.P1001, withBudget(1, 1, -1).validate());
        assertEquals(ErrorCode.P1001, withBudget(1, 1, 120001).validate());
    }

    private static MachineReport withBudget(long input, long output, long duration) {
        return new MachineReport(CASE_ID, WORKFLOW_ID, VERSION_ID, 1, MATERIAL_SHA256,
                "m", RiskLevel.LOW, Set.of(), ReviewDecision.PASS, Grade.G,
                new BigDecimal("0.99"), List.of("e:1"), input, output, duration);
    }

    @Test
    void modelVersionAndRiskBounds() {
        assertEquals(ErrorCode.P1001, withModelVersion("m".repeat(81)).validate());
        assertEquals(ErrorCode.P1001, withModelVersion(" ").validate());
        assertEquals(ErrorCode.OK, withModelVersion("m".repeat(80)).validate());
        assertEquals(ErrorCode.P1001, withRisk(null).validate());
    }

    private static MachineReport withModelVersion(String version) {
        return new MachineReport(CASE_ID, WORKFLOW_ID, VERSION_ID, 1, MATERIAL_SHA256,
                version, RiskLevel.LOW, Set.of(), ReviewDecision.PASS, Grade.G,
                new BigDecimal("0.99"), List.of("e:1"), 1, 1, 1);
    }

    private static MachineReport withRisk(RiskLevel risk) {
        return new MachineReport(CASE_ID, WORKFLOW_ID, VERSION_ID, 1, MATERIAL_SHA256,
                "m", risk, Set.of(), ReviewDecision.PASS, Grade.G,
                new BigDecimal("0.99"), List.of("e:1"), 1, 1, 1);
    }
}
