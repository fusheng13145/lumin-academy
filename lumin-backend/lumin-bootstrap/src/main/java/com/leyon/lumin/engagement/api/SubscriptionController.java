package com.leyon.lumin.engagement.api;

import com.leyon.lumin.engagement.repository.SubscriptionListItem;
import com.leyon.lumin.engagement.service.SubscriptionService;
import com.leyon.lumin.foundation.contract.ApiEnvelope;
import com.leyon.lumin.foundation.web.DemoAuthenticationFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 追更订阅接入层（docs/04 §6.1 subscription；docs/13 UGC-036 M1 一阶段）：
 * 订阅/退订/订阅列表/状态标记/偏好（免打扰窗口 + IANA 时区 + 推送开关）。
 * 收藏≠订阅（bookmark 不产生订阅行）；未成年推送闸在服务层 fail-closed；
 * 聚合通知的写入与去重随 UGC-037 接 NotificationDigestPolicy。
 */
@RestController
@RequestMapping("/api/v1/me/subscriptions")
public class SubscriptionController {

    private final SubscriptionService subscriptionService;

    public SubscriptionController(SubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    /** 订阅（幂等；重复订阅重开并保留既有偏好）。 */
    @PostMapping
    public ApiEnvelope subscribe(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestBody SubscribeRequest request) {
        subscriptionService.subscribe(subject, request.workId(),
                LocalDateTime.now(ZoneOffset.UTC));
        return ApiEnvelope.ok(Map.of("workId", request.workId(), "subscribed", true));
    }

    /** 订阅列表（订阅时间倒序；含作品业务态——下架作品前端灰显，不推送随 UGC-037）。 */
    @GetMapping
    public ApiEnvelope list(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @RequestParam(defaultValue = "20") int limit) {
        List<SubscriptionListItem> items = subscriptionService.listMine(subject);
        return ApiEnvelope.ok(items.stream()
                .map(item -> {
                    Map<String, Object> view = new LinkedHashMap<>();
                    view.put("workId", item.workId());
                    view.put("title", item.title() == null ? "" : item.title());
                    view.put("author", item.author() == null ? "" : item.author());
                    view.put("workState", item.workState());
                    view.put("subscribedAt", item.subscribedAt().toInstant(ZoneOffset.UTC)
                            .toString());
                    view.put("quietStart", item.quietStart() == null
                            ? "" : item.quietStart().toString());
                    view.put("quietEnd", item.quietEnd() == null
                            ? "" : item.quietEnd().toString());
                    view.put("pushEnabled", Boolean.TRUE.equals(item.pushEnabled()));
                    view.put("timezoneId", item.timezoneId() == null
                            ? "UTC" : item.timezoneId());
                    return view;
                })
                .toList());
    }

    /** 是否已订阅（详情页追更标记）。 */
    @GetMapping("/{workId}/status")
    public ApiEnvelope status(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String workId) {
        return ApiEnvelope.ok(Map.of("workId", workId,
                "subscribed", subscriptionService.isSubscribed(subject, workId)));
    }

    /** 偏好更新（全量 PUT：免打扰 HH:mm 窗口 + IANA 时区 + 推送开关；未成年收窄回显）。 */
    @PutMapping("/{workId}/preferences")
    public ApiEnvelope updatePreferences(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String workId,
            @RequestBody UpdatePreferencesRequest request) {
        SubscriptionService.PreferenceView view = subscriptionService.updatePreferences(
                subject, workId, request.quietStart(), request.quietEnd(),
                request.timezoneId(), request.pushEnabled(),
                LocalDateTime.now(ZoneOffset.UTC));
        return ApiEnvelope.ok(Map.of(
                "workId", view.workId(),
                "quietStart", view.quietStart().toString(),
                "quietEnd", view.quietEnd().toString(),
                "pushEnabled", view.pushEnabled(),
                "timezoneId", view.timezoneId()));
    }

    /** 退订（幂等；跨账号隔离）。 */
    @DeleteMapping("/{workId}")
    public ApiEnvelope unsubscribe(
            @RequestAttribute(DemoAuthenticationFilter.ATTRIBUTE_SUBJECT) String subject,
            @PathVariable String workId) {
        subscriptionService.unsubscribe(subject, workId, LocalDateTime.now(ZoneOffset.UTC));
        return ApiEnvelope.ok(Map.of("workId", workId, "subscribed", false));
    }

    /** 订阅请求体。 */
    public record SubscribeRequest(String workId) {
    }

    /** 偏好请求体（全量语义；非法窗口/时区 400 P1001）。 */
    public record UpdatePreferencesRequest(String quietStart, String quietEnd,
                                           String timezoneId, Boolean pushEnabled) {
    }
}
