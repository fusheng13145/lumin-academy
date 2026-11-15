package com.leyon.lumin.support;

import com.leyon.lumin.foundation.BusinessTime;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * 07 §1.1 固定夹具：全套件唯一来源，全部为合成值，彼此独立。
 *
 * <p>固定业务时钟 2026-11-12T00:00:00+08:00；任何测试都不得读取当前系统时间
 * 或使用系统默认时区的实时时钟，全部时间必须经本夹具注入。
 */
public final class FixedFixtures {

    public static final Clock BUSINESS_CLOCK = Clock.fixed(
            Instant.parse("2026-11-11T16:00:00Z"), ZoneId.of("Asia/Shanghai"));

    // 18 个合成固定 UUID，形态 00000000-0000-4000-8000-0000000000NN（NN=01…18）
    public static final UUID A = uuid(1);
    public static final UUID B = uuid(2);
    public static final UUID G = uuid(3);
    public static final UUID L1A = uuid(4);
    public static final UUID L1B = uuid(5);
    public static final UUID L2A = uuid(6);
    public static final UUID L2B = uuid(7);
    public static final UUID O = uuid(8);
    public static final UUID X = uuid(9);
    public static final UUID Y = uuid(10);
    public static final UUID J = uuid(11);
    public static final UUID K = uuid(12);
    public static final UUID Z = uuid(13);
    public static final UUID S = uuid(14);
    public static final UUID W = uuid(15);
    public static final UUID C = uuid(16);
    public static final UUID V1 = uuid(17);
    public static final UUID V2 = uuid(18);

    public static final List<UUID> ALL_IDS = List.of(
            A, B, G, L1A, L1B, L2A, L2B, O, X, Y, J, K, Z, S, W, C, V1, V2);

    public static final String SYNTHETIC_BODY = "第一段\n第二段";
    /** 仅作分类标签，无真实有害/未成年敏感细节（07 §1.1 / N-06）。 */
    public static final String LABEL_MINOR = "MINOR";
    public static final String LABEL_ILLEGAL = "ILLEGAL";

    private FixedFixtures() {
    }

    private static UUID uuid(int ordinal) {
        return UUID.fromString(String.format("00000000-0000-4000-8000-%012d", ordinal));
    }

    /** C0：13 岁（相对固定时钟反推）。 */
    public static LocalDate birthDateC0() {
        return LocalDate.of(2013, 11, 12);
    }

    /** C1：15 岁。 */
    public static LocalDate birthDateC1() {
        return LocalDate.of(2011, 11, 12);
    }

    /** C2：17 岁。 */
    public static LocalDate birthDateC2() {
        return LocalDate.of(2009, 11, 12);
    }

    /** C3：20 岁（verifiedAgeBand=C3、verificationValid=true）。 */
    public static LocalDate birthDateC3() {
        return LocalDate.of(2006, 11, 12);
    }

    /** C4：同 C3 生日但自报 20 岁、无核验；assess 必须返回 C4。 */
    public static LocalDate birthDateC4() {
        return LocalDate.of(2006, 11, 12);
    }

    /** 构造 Asia/Shanghai 生日当天 00:00 前后 1 秒/当日的固定时钟。 */
    public static Clock clockAtBirthday(LocalDate birthday, long offsetSeconds) {
        Instant shanghaiMidnight = birthday.atStartOfDay(BusinessTime.ASIA_SHANGHAI).toInstant();
        return Clock.fixed(shanghaiMidnight.plusSeconds(offsetSeconds), BusinessTime.ASIA_SHANGHAI);
    }

    /** 任意观察日的固定时钟（用于闰日矩阵）。 */
    public static Clock clockOn(LocalDate date) {
        return Clock.fixed(date.atStartOfDay(BusinessTime.ASIA_SHANGHAI).toInstant(),
                BusinessTime.ASIA_SHANGHAI);
    }
}
