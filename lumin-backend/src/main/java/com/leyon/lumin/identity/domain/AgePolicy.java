package com.leyon.lumin.identity.domain;

import com.leyon.lumin.foundation.BusinessTime;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.AccountStatus;
import com.leyon.lumin.foundation.values.AgeBand;
import com.leyon.lumin.foundation.values.Grade;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 年龄策略纯函数（01 §3.2、03 §6 / §8 单元①、docs/10 A.3.1）。
 *
 * <p>无 IO、无当前系统时间读取、无可变静态状态；所有时间由 {@link Clock} 注入。
 * 生日边界 14/16/18 为工程候选（W-01 冻结前不开放年龄升级），本策略不执行
 * 真实核验、不启用公开注册或发布（K-02 / N-01）。
 */
public final class AgePolicy {

    private AgePolicy() {
    }

    /** 周岁分层：&lt;14 C0、&lt;16 C1、&lt;18 C2、≥18 C3；生日当天即升级。 */
    public static AgeBand ageBandAt(LocalDate birthDate, LocalDate onDate) {
        if (birthDate == null || onDate == null) {
            throw new IllegalArgumentException("出生日期与观察日期不能为空");
        }
        int years = BusinessTime.fullYears(birthDate, onDate);
        if (years < BusinessTime.BOUNDARY_14) {
            return AgeBand.C0;
        }
        if (years < BusinessTime.BOUNDARY_16) {
            return AgeBand.C1;
        }
        if (years < BusinessTime.BOUNDARY_18) {
            return AgeBand.C2;
        }
        return AgeBand.C3;
    }

    /**
     * 依据已核验记录评估有效年龄层与发布资格。
     *
     * <p>步骤：① 未核验 → C4；② 已核验取「周岁/已核验/声明」中最严格值，
     * 声明更严格时触发复核；③ 监护撤销立即降级 C4 并阻断新发布；
     * ④ C2 需核验 + 有效监护同意且每件必人审，C3 需账户 ACTIVE；
     * ⑤ 账户非 ACTIVE 一律禁止发布。
     */
    public static AgeAssessment assess(AgeEvidence evidence, Clock businessClock) {
        if (evidence == null || businessClock == null) {
            throw new IllegalArgumentException("年龄证据与业务时钟不能为空");
        }
        LocalDate onDate = BusinessTime.localDate(businessClock);
        AgeBand effective;
        boolean requiresReverify = false;

        if (!evidence.verificationValid() || evidence.verifiedAgeBand() == null) {
            // 自报成年不得升级：declaredAgeBand 完全不参与升级
            effective = AgeBand.C4;
        } else {
            AgeBand computed = ageBandAt(evidence.birthDate(), onDate);
            AgeBand verified = evidence.verifiedAgeBand();
            effective = stricter(computed, verified);
            AgeBand declared = evidence.declaredAgeBand();
            if (declared != null) {
                int baseRank = Math.min(computed.restrictionRank(), verified.restrictionRank());
                if (declared.restrictionRank() < baseRank) {
                    requiresReverify = true;
                    effective = stricter(effective, declared);
                }
            }
        }

        Instant now = businessClock.instant();
        boolean revoked = evidence.guardianRevokedAt() != null
                && !evidence.guardianRevokedAt().isAfter(now);
        boolean requiresHumanReview = false;
        boolean canPublish;

        if (revoked) {
            // 03 §6：立即降级、禁新发布、置合规待复核阻断；不自动删除历史内容
            effective = AgeBand.C4;
            canPublish = false;
            requiresHumanReview = true;
        } else if (evidence.accountStatus() != AccountStatus.ACTIVE) {
            canPublish = false;
        } else {
            switch (effective) {
                case C0, C1, C4 -> canPublish = false;
                case C2 -> {
                    canPublish = evidence.verificationValid() && evidence.guardianConsentActive();
                    requiresHumanReview = true;
                }
                case C3 -> canPublish = true;
                default -> canPublish = false;
            }
        }

        boolean requiresGuardianConsent = effective == AgeBand.C0
                || effective == AgeBand.C1 || effective == AgeBand.C2;
        ErrorCode denialCode = canPublish ? ErrorCode.OK : ErrorCode.A2002;
        return new AgeAssessment(effective, canPublish, requiresGuardianConsent,
                requiresHumanReview, revoked, requiresReverify, denialCode);
    }

    /** 内容等级可读矩阵：C4 按 C1；A 级在 P0 默认关闭且需双重开启。 */
    public static boolean canRead(AgeBand effectiveBand, Grade grade,
                                  boolean adultContentEnabled, boolean adultEntryConfirmed) {
        if (effectiveBand == null || grade == null) {
            return false;
        }
        AgeBand band = effectiveBand.readingBand();
        if (grade == Grade.A) {
            return band == AgeBand.C3 && adultContentEnabled && adultEntryConfirmed;
        }
        if (grade == Grade.PG_16) {
            return band == AgeBand.C2 || band == AgeBand.C3;
        }
        return true;
    }

    /** 取严格度更高（restrictionRank 更小）的年龄层。 */
    private static AgeBand stricter(AgeBand left, AgeBand right) {
        if (right == null) {
            return left;
        }
        if (left == null) {
            return right;
        }
        return left.restrictionRank() <= right.restrictionRank() ? left : right;
    }

    /** 列表过滤便捷判定（等价于 canRead(band, grade, false, false)：A 级默认关闭）。 */
    public static boolean canReadPublic(AgeBand band, Grade grade) {
        return canRead(band, grade, false, false);
    }
}
