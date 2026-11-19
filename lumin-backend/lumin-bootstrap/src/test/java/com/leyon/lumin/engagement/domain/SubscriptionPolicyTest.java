package com.leyon.lumin.engagement.domain;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 订阅偏好纯策略单测（docs/04 §6.1；docs/13 UGC-036）：免打扰窗口解析与
 * 跨午夜判定、IANA 时区解析 fail-closed、未成年推送闸。
 */
class SubscriptionPolicyTest {

    @Test
    void parsesValidWindowsIncludingWrapAround() {
        SubscriptionPolicy.QuietWindow window =
                SubscriptionPolicy.parseQuietWindow("22:00", "08:00");
        assertThat(window).isNotNull();
        assertThat(window.start()).isEqualTo(LocalTime.of(22, 0));
        assertThat(window.end()).isEqualTo(LocalTime.of(8, 0));

        SubscriptionPolicy.QuietWindow normal =
                SubscriptionPolicy.parseQuietWindow("09:00", "12:00");
        assertThat(normal).isNotNull();
        assertThat(normal.start()).isBefore(normal.end());
    }

    @Test
    void rejectsNullBlankMalformedAndEmptyWindows() {
        assertThat(SubscriptionPolicy.parseQuietWindow(null, "08:00")).isNull();
        assertThat(SubscriptionPolicy.parseQuietWindow("22:00", null)).isNull();
        assertThat(SubscriptionPolicy.parseQuietWindow("bad", "08:00")).isNull();
        assertThat(SubscriptionPolicy.parseQuietWindow("22:00", "25:00")).isNull();
        // start == end：空窗口拒绝（全天免打扰由 push_enabled=false 表达）
        assertThat(SubscriptionPolicy.parseQuietWindow("08:00", "08:00")).isNull();
    }

    @Test
    void detectsQuietTimeAcrossMidnight() {
        SubscriptionPolicy.QuietWindow wrap =
                SubscriptionPolicy.parseQuietWindow("22:00", "08:00");
        assertThat(SubscriptionPolicy.isQuiet(wrap, LocalTime.of(23, 0))).isTrue();
        assertThat(SubscriptionPolicy.isQuiet(wrap, LocalTime.of(2, 0))).isTrue();
        assertThat(SubscriptionPolicy.isQuiet(wrap, LocalTime.of(7, 59))).isTrue();
        assertThat(SubscriptionPolicy.isQuiet(wrap, LocalTime.of(22, 0))).isTrue();
        // 终点开区间、窗口外
        assertThat(SubscriptionPolicy.isQuiet(wrap, LocalTime.of(8, 0))).isFalse();
        assertThat(SubscriptionPolicy.isQuiet(wrap, LocalTime.of(12, 0))).isFalse();
        assertThat(SubscriptionPolicy.isQuiet(wrap, LocalTime.of(21, 59))).isFalse();

        SubscriptionPolicy.QuietWindow normal =
                SubscriptionPolicy.parseQuietWindow("09:00", "12:00");
        assertThat(SubscriptionPolicy.isQuiet(normal, LocalTime.of(10, 0))).isTrue();
        assertThat(SubscriptionPolicy.isQuiet(normal, LocalTime.of(12, 0))).isFalse();
        assertThat(SubscriptionPolicy.isQuiet(normal, LocalTime.of(8, 0))).isFalse();
    }

    @Test
    void quietCheckIsFailSafeOnNullInputs() {
        SubscriptionPolicy.QuietWindow window =
                SubscriptionPolicy.parseQuietWindow("22:00", "08:00");
        assertThat(SubscriptionPolicy.isQuiet(null, LocalTime.of(23, 0))).isFalse();
        assertThat(SubscriptionPolicy.isQuiet(window, null)).isFalse();
    }

    @Test
    void parsesIanaZonesAndRejectsUnknown() {
        assertThat(SubscriptionPolicy.parseZoneId("Asia/Shanghai"))
                .isEqualTo(ZoneId.of("Asia/Shanghai"));
        assertThat(SubscriptionPolicy.parseZoneId("UTC")).isEqualTo(ZoneId.of("UTC"));
        assertThat(SubscriptionPolicy.parseZoneId("Mars/Phobos")).isNull();
        assertThat(SubscriptionPolicy.parseZoneId("not a zone")).isNull();
        assertThat(SubscriptionPolicy.parseZoneId(null)).isNull();
        assertThat(SubscriptionPolicy.parseZoneId("  ")).isNull();
    }

    @Test
    void pushGateIsFailClosedForMinorsAndUnverified() {
        assertThat(SubscriptionPolicy.pushAllowed(true)).isTrue();
        assertThat(SubscriptionPolicy.pushAllowed(false)).isFalse();
        assertThat(SubscriptionPolicy.isVerifiedAdult("C3")).isTrue();
        assertThat(SubscriptionPolicy.isVerifiedAdult("C0")).isFalse();
        assertThat(SubscriptionPolicy.isVerifiedAdult("C1")).isFalse();
        assertThat(SubscriptionPolicy.isVerifiedAdult("C2")).isFalse();
        assertThat(SubscriptionPolicy.isVerifiedAdult("C4")).isFalse();
        assertThat(SubscriptionPolicy.isVerifiedAdult(null)).isFalse();
        assertThat(SubscriptionPolicy.isVerifiedAdult("")).isFalse();
    }
}
