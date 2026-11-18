package com.leyon.lumin.moderation.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.Grade;
import com.leyon.lumin.foundation.values.RiskFlag;
import com.leyon.lumin.foundation.values.RiskLevel;
import com.leyon.lumin.foundation.values.ReviewDecision;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 机审报告（契约 {@code MachineReport} / POST /internal/v1/machine-reports，05 §4.3）。
 *
 * <p>报告按不可信输入处理（INV-07）：任何校验失败整份拒绝（400/P1001 或隔离），
 * 不局部采信；无 APPROVED、无公开指针、有告警（T18）。
 * materialSha256 绑定不可变完整审核材料快照，不是仅正文哈希（05 §4.3）；
 * 服务层还须核验 versionId / workflowId / caseId / policyVersion 与权威记录一致。
 * unknown 字段的拒绝由反序列化层按契约 additionalProperties=false 执行，不在本记录范围。
 */
public record MachineReport(UUID caseId, UUID workflowId, UUID versionId, long policyVersion,
                            String materialSha256, String modelVersion, RiskLevel risk,
                            Set<RiskFlag> riskFlags, ReviewDecision suggestedDecision,
                            Grade grade, BigDecimal confidence, List<String> evidenceRefs,
                            long inputTokens, long outputTokens, long durationMs) {

    public static final int MAX_MODEL_VERSION_LENGTH = 80;
    public static final int MAX_RISK_FLAGS = 5;
    public static final int MIN_EVIDENCE_REFS = 1;
    public static final int MAX_EVIDENCE_REFS = 10;
    public static final int MAX_EVIDENCE_REF_LENGTH = 200;
    public static final long MAX_INPUT_TOKENS = 40000;
    public static final long MAX_OUTPUT_TOKENS = 2000;
    public static final long MAX_DURATION_MS = 120000;
    /** NUMERIC(5,4)：confidence 精度最多 4 位小数。 */
    public static final int MAX_CONFIDENCE_SCALE = 4;

    private static final String SHA256_HEX_PATTERN = "[a-f0-9]{64}";
    private static final String EVIDENCE_REF_PATTERN = "[A-Za-z0-9:_-]+";
    private static final BigDecimal ONE = BigDecimal.ONE;

    /** 契约级校验：返回 {@link ErrorCode#OK} 表示合法，否则为拒绝错误码（400/P1001）。 */
    public ErrorCode validate() {
        if (caseId == null || workflowId == null || versionId == null) {
            return ErrorCode.P1001;
        }
        if (policyVersion < 1 || policyVersion > 9007199254740991L) {
            return ErrorCode.P1001;
        }
        if (materialSha256 == null || !materialSha256.matches(SHA256_HEX_PATTERN)) {
            return ErrorCode.P1001;
        }
        if (modelVersion == null || modelVersion.isBlank()
                || modelVersion.length() > MAX_MODEL_VERSION_LENGTH) {
            return ErrorCode.P1001;
        }
        if (risk == null) {
            return ErrorCode.P1001;
        }
        if (riskFlags == null || riskFlags.size() > MAX_RISK_FLAGS) {
            return ErrorCode.P1001;
        }
        if (ReviewDecisionRule.check(suggestedDecision, grade) != ErrorCode.OK) {
            return ErrorCode.P1001;
        }
        if (confidence == null
                || confidence.scale() > MAX_CONFIDENCE_SCALE
                || confidence.compareTo(BigDecimal.ZERO) < 0
                || confidence.compareTo(ONE) > 0) {
            return ErrorCode.P1001;
        }
        if (!validateEvidenceRefs()) {
            return ErrorCode.P1001;
        }
        if (inputTokens < 0 || inputTokens > MAX_INPUT_TOKENS
                || outputTokens < 0 || outputTokens > MAX_OUTPUT_TOKENS
                || durationMs < 0 || durationMs > MAX_DURATION_MS) {
            return ErrorCode.P1001;
        }
        return ErrorCode.OK;
    }

    private boolean validateEvidenceRefs() {
        if (evidenceRefs == null
                || evidenceRefs.size() < MIN_EVIDENCE_REFS
                || evidenceRefs.size() > MAX_EVIDENCE_REFS
                || evidenceRefs.size() != new HashSet<>(evidenceRefs).size()) {
            return false;
        }
        for (String ref : evidenceRefs) {
            if (ref == null || ref.isBlank()
                    || ref.length() > MAX_EVIDENCE_REF_LENGTH
                    || !ref.matches(EVIDENCE_REF_PATTERN)) {
                return false;
            }
        }
        return true;
    }
}
