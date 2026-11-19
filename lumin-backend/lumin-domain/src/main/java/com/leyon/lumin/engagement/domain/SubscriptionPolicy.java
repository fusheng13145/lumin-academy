package com.leyon.lumin.engagement.domain;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * 订阅偏好纯策略（docs/04 §6.1 subscription；docs/13 UGC-036）：
 * 免打扰窗口支持跨午夜（如 22:00→08:00）；start==end 视为空窗口拒绝
 * （「全天免打扰」由 push_enabled=false 表达，语义不二义）；时区为 IANA
 * ZoneId（fail-closed，非法即拒绝）；未成年/未核实档案推送闸 fail-closed——
 * 仅 C3（已核实成年）可开启 push_enabled，同意范围随 UGC-003 接入后放宽。
 * 无 IO、无时钟读取。
 */
public final class SubscriptionPolicy {

    /** 默认免打扰窗口（22:00→08:00 跨午夜）。 */
    public static final LocalTime DEFAULT_QUIET_START = LocalTime.of(22, 0);
    public static final LocalTime DEFAULT_QUIET_END = LocalTime.of(8, 0);

    /** 已核实成年带（AgeBand.C3）；其余带（含 C4 未核实）一律未成年口径。 */
    public static final String VERIFIED_ADULT_BAND = "C3";

    private SubscriptionPolicy() {
    }

    /** 免打扰窗口（HH:mm 解析；start==end 拒绝）。 */
    public record QuietWindow(LocalTime start, LocalTime end) {
    }

    /**
     * 解析免打扰窗口。
     *
     * @return 合法窗口；输入为 null 或格式非法或 start==end 时返回 null（由调用方转 400）
     */
    public static QuietWindow parseQuietWindow(String quietStart, String quietEnd) {
        if (quietStart == null || quietEnd == null) {
            return null;
        }
        try {
            LocalTime start = LocalTime.parse(quietStart);
            LocalTime end = LocalTime.parse(quietEnd);
            if (start.equals(end)) {
                return null;
            }
            return new QuietWindow(start, end);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** 指定本地时刻是否落在免打扰窗口（支持跨午夜环绕）。 */
    public static boolean isQuiet(QuietWindow window, LocalTime localTime) {
        if (window == null || localTime == null) {
            return false;
        }
        if (window.start().isBefore(window.end())) {
            return !localTime.isBefore(window.start()) && localTime.isBefore(window.end());
        }
        // 跨午夜：start > end，如 22:00→08:00
        return !localTime.isBefore(window.start()) || localTime.isBefore(window.end());
    }

    /**
     * 解析 IANA 时区。
     *
     * @return 合法 ZoneId；输入为 null/非法/未知时返回 null（由调用方转 400）
     */
    public static ZoneId parseZoneId(String timezoneId) {
        if (timezoneId == null || timezoneId.isBlank()) {
            return null;
        }
        try {
            return ZoneId.of(timezoneId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 推送闸（UGC-036「未成年推送按同意范围控制」；UGC-003 同意范围接入前
     * fail-closed：仅已核实成年可开推送，其余带强制 false，不报错只收窄）。
     */
    public static boolean pushAllowed(boolean verifiedAdult) {
        return verifiedAdult;
    }

    /** 是否已核实成年（按 user_profile.effective_age_band 口径）。 */
    public static boolean isVerifiedAdult(String effectiveAgeBand) {
        return VERIFIED_ADULT_BAND.equals(effectiveAgeBand);
    }
}
