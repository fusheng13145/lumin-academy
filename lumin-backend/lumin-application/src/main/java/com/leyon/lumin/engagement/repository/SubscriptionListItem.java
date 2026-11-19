package com.leyon.lumin.engagement.repository;

import java.time.LocalDateTime;
import java.time.LocalTime;

/** 订阅列表行（UGC-036：含作品元数据与免打扰/推送偏好展示）。 */
public record SubscriptionListItem(
        String subscriptionId,
        String workId,
        LocalDateTime subscribedAt,
        Boolean enabled,
        LocalTime quietStart,
        LocalTime quietEnd,
        Boolean pushEnabled,
        String timezoneId,
        String workState,
        String title,
        String author) {
}
