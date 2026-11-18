package com.leyon.lumin.foundation;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 业务时间与周岁工具（01 §3.2、07 §1.1）。
 *
 * <p>纯工具类：不含当前系统时间读取，所有时间由调用方注入 {@link Clock}。
 * 生日边界 14/16/18 为 01 §3.2 的<strong>工程候选</strong>，法务 W-01
 * 冻结前不开放年龄升级（K-02）。
 */
public final class BusinessTime {

    public static final ZoneId ASIA_SHANGHAI = ZoneId.of("Asia/Shanghai");
    public static final Instant FIXTURE_INSTANT = Instant.parse("2026-11-11T16:00:00Z");

    public static final int BOUNDARY_14 = 14;
    public static final int BOUNDARY_16 = 16;
    public static final int BOUNDARY_18 = 18;

    private BusinessTime() {
    }

    /** 固定业务时钟 2026-11-12T00:00:00+08:00，P0-A 唯一允许的时间来源。 */
    public static Clock fixedBusinessClock() {
        return Clock.fixed(FIXTURE_INSTANT, ASIA_SHANGHAI);
    }

    /** 业务自然日（Asia/Shanghai）。 */
    public static LocalDate localDate(Clock clock) {
        if (clock == null) {
            throw new IllegalArgumentException("业务时钟不能为空");
        }
        return clock.instant().atZone(ASIA_SHANGHAI).toLocalDate();
    }

    /** 存储与事件时间统一为 UTC 的 Instant；入参本身即为 UTC 时间轴上的点。 */
    public static Instant toUtc(Instant instant) {
        if (instant == null) {
            throw new IllegalArgumentException("时间不能为空");
        }
        return instant;
    }

    /**
     * 法定生日：2/29 出生且观察年非闰年时按 3/1，否则取观察年同月同日。
     */
    public static LocalDate legalBirthday(LocalDate birthDate, int onYear) {
        if (birthDate == null) {
            throw new IllegalArgumentException("出生日期不能为空");
        }
        if (birthDate.getMonthValue() == 2 && birthDate.getDayOfMonth() == 29
                && !java.time.Year.isLeap(onYear)) {
            return LocalDate.of(onYear, 3, 1);
        }
        return LocalDate.of(onYear, birthDate.getMonthValue(), birthDate.getDayOfMonth());
    }

    /** 周岁：生日当天即已 +1（边界 14/16/18 岁生日当天生效）。 */
    public static int fullYears(LocalDate birthDate, LocalDate onDate) {
        if (birthDate == null || onDate == null) {
            throw new IllegalArgumentException("出生日期与观察日期不能为空");
        }
        LocalDate legalBirthday = legalBirthday(birthDate, onDate.getYear());
        int years = onDate.getYear() - birthDate.getYear();
        return onDate.isBefore(legalBirthday) ? years - 1 : years;
    }
}
