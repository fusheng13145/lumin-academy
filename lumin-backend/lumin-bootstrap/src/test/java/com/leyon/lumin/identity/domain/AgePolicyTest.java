package com.leyon.lumin.identity.domain;

import com.leyon.lumin.foundation.BusinessTime;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.values.AccountStatus;
import com.leyon.lumin.foundation.values.AgeBand;
import com.leyon.lumin.foundation.values.Grade;
import com.leyon.lumin.support.FixedFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T01–T03（07 §2.1）：年龄边界秒级、闰日矩阵、C4 自报成年、监护授权/撤销、
 * 声明更严格触发复核、内容等级可读矩阵。全部时间由固定 Clock 注入。
 */
class AgePolicyTest {

    private static AgeEvidence verified(LocalDate birthDate, AgeBand declared, AgeBand verified,
                                        boolean consent, Instant revokedAt, AccountStatus status) {
        return new AgeEvidence(FixedFixtures.A, birthDate, declared, verified, true,
                consent, revokedAt, status);
    }

    private static AgeEvidence unverified(LocalDate birthDate, AgeBand declared) {
        return new AgeEvidence(FixedFixtures.A, birthDate, declared, null, false,
                false, null, AccountStatus.ACTIVE);
    }

    private static AgeBand bandOf(int years) {
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

    private static Stream<Arguments> birthdayMatrix() {
        List<Arguments> cases = new ArrayList<>();
        int[] ages = {13, 14, 15, 16, 17, 18, 19, 21};
        long[] offsets = {-1L, 0L, 1L};
        for (int age : ages) {
            for (long offset : offsets) {
                cases.add(Arguments.of(age, offset));
            }
        }
        return cases.stream();
    }

    /** 13/14/15/16/17/18/19/21 岁 × 生日前 1 秒 / 当日 / 后 1 秒。 */
    @ParameterizedTest
    @MethodSource("birthdayMatrix")
    void ageBandAtRespectsBirthdaySecondBoundary(int age, long offsetSeconds) {
        LocalDate birthDate = LocalDate.of(2026 - age, 11, 12);
        Clock clock = Clock.fixed(BusinessTime.FIXTURE_INSTANT.plusSeconds(offsetSeconds),
                BusinessTime.ASIA_SHANGHAI);
        LocalDate onDate = BusinessTime.localDate(clock);
        int expectedYears = offsetSeconds < 0 ? age - 1 : age;
        assertEquals(expectedYears, BusinessTime.fullYears(birthDate, onDate),
                "周岁必须按公历生日当天变更");
        assertEquals(bandOf(expectedYears), AgePolicy.ageBandAt(birthDate, onDate));
    }

    @Test
    void ageBandAtUpgradesOnBirthdayForBoundaries14And16And18() {
        assertEquals(AgeBand.C0, AgePolicy.ageBandAt(LocalDate.of(2012, 11, 12),
                LocalDate.of(2026, 11, 11)));
        assertEquals(AgeBand.C1, AgePolicy.ageBandAt(LocalDate.of(2012, 11, 12),
                LocalDate.of(2026, 11, 12)));
        assertEquals(AgeBand.C1, AgePolicy.ageBandAt(LocalDate.of(2010, 11, 12),
                LocalDate.of(2026, 11, 11)));
        assertEquals(AgeBand.C2, AgePolicy.ageBandAt(LocalDate.of(2010, 11, 12),
                LocalDate.of(2026, 11, 12)));
        assertEquals(AgeBand.C2, AgePolicy.ageBandAt(LocalDate.of(2008, 11, 12),
                LocalDate.of(2026, 11, 11)));
        assertEquals(AgeBand.C3, AgePolicy.ageBandAt(LocalDate.of(2008, 11, 12),
                LocalDate.of(2026, 11, 12)));
    }

    /** 2/29 出生在非闰年按 3/1 变更年龄（01 §3.2、07 §1.1）。 */
    @Test
    void leapDayBirthdayUsesMarchFirstInNonLeapYears() {
        LocalDate leapBirth = LocalDate.of(2012, 2, 29);
        assertEquals(12, BusinessTime.fullYears(leapBirth, LocalDate.of(2025, 2, 28)));
        assertEquals(13, BusinessTime.fullYears(leapBirth, LocalDate.of(2025, 3, 1)));
        assertEquals(AgeBand.C0, AgePolicy.ageBandAt(leapBirth, LocalDate.of(2025, 2, 28)));
        assertEquals(AgeBand.C0, AgePolicy.ageBandAt(leapBirth, LocalDate.of(2025, 3, 1)));
        assertEquals(LocalDate.of(2025, 3, 1), BusinessTime.legalBirthday(leapBirth, 2025));
        assertEquals(LocalDate.of(2024, 2, 29), BusinessTime.legalBirthday(leapBirth, 2024));

        LocalDate older = LocalDate.of(2004, 2, 29);
        assertEquals(13, BusinessTime.fullYears(older, LocalDate.of(2018, 2, 28)));
        assertEquals(14, BusinessTime.fullYears(older, LocalDate.of(2018, 3, 1)));
        assertEquals(AgeBand.C1, AgePolicy.ageBandAt(older, LocalDate.of(2018, 3, 1)));
        assertEquals(16, BusinessTime.fullYears(older, LocalDate.of(2020, 2, 29)));
        assertEquals(AgeBand.C2, AgePolicy.ageBandAt(older, LocalDate.of(2020, 2, 29)));
    }

    @Test
    void ageBandAtRejectsNullInput() {
        assertThrows(IllegalArgumentException.class,
                () -> AgePolicy.ageBandAt(null, LocalDate.of(2026, 11, 12)));
        assertThrows(IllegalArgumentException.class,
                () -> AgePolicy.ageBandAt(LocalDate.of(2000, 1, 1), null));
    }

    /** T02：C4 自报 20 岁不得升 C3；未核验一律按 C4 且禁止发布。 */
    @Test
    void unverifiedDeclaredAdultStaysC4AndCannotPublish() {
        AgeAssessment result = AgePolicy.assess(
                unverified(FixedFixtures.birthDateC4(), AgeBand.C3), FixedFixtures.BUSINESS_CLOCK);
        assertEquals(AgeBand.C4, result.effectiveAgeBand());
        assertFalse(result.canPublish());
        assertEquals(ErrorCode.A2002, result.denialCode());
        assertFalse(result.requiresReverify());
    }

    @Test
    void assessmentIsDeterministicWithInjectedClock() {
        AgeEvidence evidence = verified(FixedFixtures.birthDateC3(), AgeBand.C3, AgeBand.C3,
                false, null, AccountStatus.ACTIVE);
        assertEquals(AgePolicy.assess(evidence, FixedFixtures.BUSINESS_CLOCK),
                AgePolicy.assess(evidence, FixedFixtures.BUSINESS_CLOCK));
    }

    /** T03：C2 无同意禁止发布；授权后仍每件必人审。 */
    @Test
    void c2RequiresGuardianConsentAndAlwaysHumanReview() {
        AgeAssessment withoutConsent = AgePolicy.assess(
                verified(FixedFixtures.birthDateC2(), AgeBand.C2, AgeBand.C2,
                        false, null, AccountStatus.ACTIVE), FixedFixtures.BUSINESS_CLOCK);
        assertEquals(AgeBand.C2, withoutConsent.effectiveAgeBand());
        assertFalse(withoutConsent.canPublish());
        assertTrue(withoutConsent.requiresGuardianConsent());
        assertTrue(withoutConsent.requiresHumanReview());

        AgeAssessment withConsent = AgePolicy.assess(
                verified(FixedFixtures.birthDateC2(), AgeBand.C2, AgeBand.C2,
                        true, null, AccountStatus.ACTIVE), FixedFixtures.BUSINESS_CLOCK);
        assertTrue(withConsent.canPublish());
        assertTrue(withConsent.requiresHumanReview());
        assertEquals(ErrorCode.OK, withConsent.denialCode());
    }

    /** T03：监护撤销立即降级 C4、禁止新发布、置复核阻断；未来撤销不生效。 */
    @Test
    void guardianRevocationImmediatelyDowngradesAndBlocksPublishing() {
        Instant past = BusinessTime.FIXTURE_INSTANT.minusSeconds(1);
        AgeAssessment revoked = AgePolicy.assess(
                verified(FixedFixtures.birthDateC2(), AgeBand.C2, AgeBand.C2,
                        true, past, AccountStatus.ACTIVE), FixedFixtures.BUSINESS_CLOCK);
        assertEquals(AgeBand.C4, revoked.effectiveAgeBand());
        assertFalse(revoked.canPublish());
        assertTrue(revoked.blockedByGuardianRevocation());
        assertTrue(revoked.requiresHumanReview());

        Instant future = BusinessTime.FIXTURE_INSTANT.plusSeconds(1);
        AgeAssessment notYet = AgePolicy.assess(
                verified(FixedFixtures.birthDateC2(), AgeBand.C2, AgeBand.C2,
                        true, future, AccountStatus.ACTIVE), FixedFixtures.BUSINESS_CLOCK);
        assertEquals(AgeBand.C2, notYet.effectiveAgeBand());
        assertFalse(notYet.blockedByGuardianRevocation());
        assertTrue(notYet.canPublish());
    }

    /** 声明比已核验更严格时按更严格结果限制并触发复核（01 §3.2）。 */
    @Test
    void stricterDeclarationDowngradesAndRequiresReverify() {
        AgeAssessment stricter = AgePolicy.assess(
                verified(FixedFixtures.birthDateC3(), AgeBand.C0, AgeBand.C3,
                        false, null, AccountStatus.ACTIVE), FixedFixtures.BUSINESS_CLOCK);
        assertEquals(AgeBand.C0, stricter.effectiveAgeBand());
        assertTrue(stricter.requiresReverify());
        assertFalse(stricter.canPublish());

        AgeAssessment normal = AgePolicy.assess(
                verified(FixedFixtures.birthDateC3(), AgeBand.C3, AgeBand.C3,
                        false, null, AccountStatus.ACTIVE), FixedFixtures.BUSINESS_CLOCK);
        assertEquals(AgeBand.C3, normal.effectiveAgeBand());
        assertFalse(normal.requiresReverify());
        assertTrue(normal.canPublish());
    }

    @Test
    void nonActiveAccountCannotPublish() {
        AgeAssessment suspended = AgePolicy.assess(
                verified(FixedFixtures.birthDateC3(), AgeBand.C3, AgeBand.C3,
                        false, null, AccountStatus.SUSPENDED), FixedFixtures.BUSINESS_CLOCK);
        assertFalse(suspended.canPublish());
        assertEquals(ErrorCode.A2002, suspended.denialCode());
    }

    /** 内容等级可读矩阵：A 级在 P0 默认关闭；grade 为空默认拒绝；C4 按 C1。 */
    @Test
    void canReadFollowsAgeBandAndGradeMatrix() {
        assertTrue(AgePolicy.canRead(AgeBand.C0, Grade.G, false, false));
        assertTrue(AgePolicy.canRead(AgeBand.C0, Grade.PG_12, false, false));
        assertFalse(AgePolicy.canRead(AgeBand.C0, Grade.PG_16, false, false));
        assertFalse(AgePolicy.canRead(AgeBand.C0, Grade.A, true, true));

        assertTrue(AgePolicy.canRead(AgeBand.C1, Grade.PG_12, false, false));
        assertFalse(AgePolicy.canRead(AgeBand.C1, Grade.PG_16, false, false));

        assertTrue(AgePolicy.canRead(AgeBand.C2, Grade.PG_16, false, false));
        assertFalse(AgePolicy.canRead(AgeBand.C2, Grade.A, true, true));

        assertTrue(AgePolicy.canRead(AgeBand.C3, Grade.G, false, false));
        assertTrue(AgePolicy.canRead(AgeBand.C3, Grade.PG_16, false, false));
        assertFalse(AgePolicy.canRead(AgeBand.C3, Grade.A, false, false));
        assertFalse(AgePolicy.canRead(AgeBand.C3, Grade.A, true, false));
        assertFalse(AgePolicy.canRead(AgeBand.C3, Grade.A, false, true));
        assertTrue(AgePolicy.canRead(AgeBand.C3, Grade.A, true, true));

        assertTrue(AgePolicy.canRead(AgeBand.C4, Grade.PG_12, false, false));
        assertFalse(AgePolicy.canRead(AgeBand.C4, Grade.PG_16, false, false));

        assertFalse(AgePolicy.canRead(AgeBand.C3, null, true, true));
        assertFalse(AgePolicy.canRead(null, Grade.G, false, false));
    }

    /** 补充：时间工具的空值防护与闰日分支、年龄层辅助方法。 */
    @Test
    void utilityNullGuardsAndLeapBranchesAreExplicit() {
        assertThrows(IllegalArgumentException.class,
                () -> AgePolicy.assess(null, FixedFixtures.BUSINESS_CLOCK));
        assertThrows(IllegalArgumentException.class,
                () -> AgePolicy.assess(unverified(FixedFixtures.birthDateC4(), AgeBand.C3), null));
        assertThrows(IllegalArgumentException.class, () -> BusinessTime.localDate(null));
        assertThrows(IllegalArgumentException.class, () -> BusinessTime.toUtc(null));
        assertThrows(IllegalArgumentException.class, () -> BusinessTime.legalBirthday(null, 2025));
        assertThrows(IllegalArgumentException.class,
                () -> BusinessTime.fullYears(null, LocalDate.of(2025, 1, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> BusinessTime.fullYears(LocalDate.of(2000, 1, 1), null));
        assertEquals(Instant.parse("2026-11-11T16:00:00Z"),
                BusinessTime.toUtc(BusinessTime.FIXTURE_INSTANT));
        assertEquals(LocalDate.of(2026, 11, 12),
                BusinessTime.localDate(BusinessTime.fixedBusinessClock()));
        assertEquals(LocalDate.of(2024, 2, 29),
                BusinessTime.legalBirthday(LocalDate.of(2000, 2, 29), 2024));
        assertEquals(LocalDate.of(2025, 3, 1),
                BusinessTime.legalBirthday(LocalDate.of(2000, 2, 29), 2025));
        assertEquals(LocalDate.of(2025, 5, 6),
                BusinessTime.legalBirthday(LocalDate.of(2000, 5, 6), 2025));
    }

    @Test
    void declaredNullAndAgeBandHelpersAreExplicit() {
        AgeAssessment noDeclaration = AgePolicy.assess(verified(
                FixedFixtures.birthDateC3(), null, AgeBand.C3, false, null,
                AccountStatus.ACTIVE), FixedFixtures.BUSINESS_CLOCK);
        assertEquals(AgeBand.C3, noDeclaration.effectiveAgeBand());
        assertFalse(noDeclaration.requiresReverify());
        assertTrue(AgeBand.C3.isVerifiedAdult());
        assertFalse(AgeBand.C4.isVerifiedAdult());
        assertEquals(AgeBand.C1, AgeBand.C4.readingBand());
        assertEquals(AgeBand.C2, AgeBand.C2.readingBand());
        for (AgeBand band : AgeBand.values()) {
            assertTrue(band.restrictionRank() >= 0);
        }
    }
}
