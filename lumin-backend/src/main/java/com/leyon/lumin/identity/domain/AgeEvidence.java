package com.leyon.lumin.identity.domain;

import com.leyon.lumin.foundation.values.AccountStatus;
import com.leyon.lumin.foundation.values.AgeBand;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 已核验记录的纯数据输入（01 §3.2、03 §8 单元①）。
 *
 * <p>不接受「自报结论」字段：declaredAgeBand 只用于更严格限制与触发复核，
 * 绝不参与年龄升级（INV-07）。
 */
public record AgeEvidence(
        UUID subjectId,
        LocalDate birthDate,
        AgeBand declaredAgeBand,
        AgeBand verifiedAgeBand,
        boolean verificationValid,
        boolean guardianConsentActive,
        Instant guardianRevokedAt,
        AccountStatus accountStatus) {
}
