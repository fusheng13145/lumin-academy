package com.leyon.lumin.engagement.service;

import com.leyon.lumin.engagement.domain.SubscriptionPolicy;
import com.leyon.lumin.engagement.repository.SubscriptionListItem;
import com.leyon.lumin.engagement.repository.SubscriptionMapper;
import com.leyon.lumin.engagement.repository.SubscriptionRow;
import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.LuminBusinessException;
import com.leyon.lumin.foundation.audit.AuditRecorder;
import com.leyon.lumin.foundation.port.ProfileBandPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * 追更订阅应用服务（docs/04 §6.1 subscription；docs/13 UGC-036 M1 一阶段）：
 * 订阅/退订/偏好（免打扰窗口 + 时区 + 推送开关）。收藏≠订阅（bookmark 不产生
 * 订阅行）；重复订阅仅重开保留既有偏好；退订幂等删行（重订阅回默认偏好）；
 * 未成年/未核实档案推送闸 fail-closed（仅 C3 可开推送，同意范围随 UGC-003
 * 接入放宽）；通知聚合去重（每作品每接收者每自然日 1 条）由 UGC-037 经
 * NotificationDigestPolicy + notification_digest 表接线。
 */
@Service
public class SubscriptionService {

    /** 偏好更新回显（推送闸收窄后的生效值，客户端以此为准）。 */
    public record PreferenceView(String workId, LocalTime quietStart, LocalTime quietEnd,
                                 boolean pushEnabled, String timezoneId) {
    }

    private final SubscriptionMapper subscriptionMapper;
    private final ProfileBandPort profileBandPort;
    private final AuditRecorder auditRecorder;

    public SubscriptionService(SubscriptionMapper subscriptionMapper,
                               ProfileBandPort profileBandPort,
                               AuditRecorder auditRecorder) {
        this.subscriptionMapper = subscriptionMapper;
        this.profileBandPort = profileBandPort;
        this.auditRecorder = auditRecorder;
    }

    /** 订阅（幂等；重复订阅重开不报错；默认推送关、免打扰 22:00→08:00、UTC）。 */
    @Transactional
    public void subscribe(String subject, String workId, LocalDateTime now) {
        SubscriptionRow row = new SubscriptionRow(UUID.randomUUID().toString(), subject,
                workId, true, SubscriptionPolicy.DEFAULT_QUIET_START,
                SubscriptionPolicy.DEFAULT_QUIET_END, false, "UTC",
                now, subject, now, subject, 1L);
        subscriptionMapper.upsert(row);
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, "READER",
                "subscription.added", "subscription", workId, "订阅追更",
                traceId(), now.toInstant(java.time.ZoneOffset.UTC)));
    }

    /** 退订（幂等；跨账号隔离——仅删除本人订阅行）。 */
    @Transactional
    public void unsubscribe(String subject, String workId, LocalDateTime now) {
        subscriptionMapper.deleteByUserAndWork(subject, workId);
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, "READER",
                "subscription.removed", "subscription", workId, "退订追更",
                traceId(), now.toInstant(java.time.ZoneOffset.UTC)));
    }

    /**
     * 偏好更新（全量 PUT 语义：免打扰窗口/时区/推送必填）。非法窗口、
     * start==end、非法时区 → 400 P1001；未订阅 → 404 B2004；未成年/未核实
     * 档案推送请求静默收窄为 false（fail-closed，回显生效值）。
     */
    @Transactional
    public PreferenceView updatePreferences(String subject, String workId,
                                            String quietStart, String quietEnd,
                                            String timezoneId, Boolean pushRequested,
                                            LocalDateTime now) {
        SubscriptionPolicy.QuietWindow window =
                SubscriptionPolicy.parseQuietWindow(quietStart, quietEnd);
        if (window == null) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        ZoneId zone = SubscriptionPolicy.parseZoneId(timezoneId);
        if (zone == null) {
            throw new LuminBusinessException(ErrorCode.P1001);
        }
        SubscriptionRow existing = subscriptionMapper.findByUserAndWork(subject, workId);
        if (existing == null || !Boolean.TRUE.equals(existing.enabled())) {
            throw new LuminBusinessException(ErrorCode.B2004);
        }
        boolean verifiedAdult = isVerifiedAdult(subject);
        boolean pushEnabled = pushRequested != null && pushRequested
                && SubscriptionPolicy.pushAllowed(verifiedAdult);
        SubscriptionRow updated = new SubscriptionRow(existing.id(), subject, workId,
                existing.enabled(), window.start(), window.end(), pushEnabled,
                zone.getId(), existing.createdAt(), existing.createdBy(), now, subject,
                existing.lockVersion());
        subscriptionMapper.updatePreferences(updated);
        auditRecorder.record(new AuditRecorder.AuditEntry(subject, "READER",
                "subscription.preferences.updated", "subscription", workId,
                verifiedAdult ? "更新订阅偏好" : "更新订阅偏好（推送闸收窄：非已核实成年）",
                traceId(), now.toInstant(java.time.ZoneOffset.UTC)));
        return new PreferenceView(workId, window.start(), window.end(), pushEnabled,
                zone.getId());
    }

    /** 是否已订阅（详情页标记）。 */
    @Transactional(readOnly = true)
    public boolean isSubscribed(String subject, String workId) {
        return subscriptionMapper.existsEnabled(subject, workId) > 0;
    }

    /** 订阅列表（订阅时间倒序；含作品业务态与偏好展示）。 */
    @Transactional(readOnly = true)
    public List<SubscriptionListItem> listMine(String subject) {
        return subscriptionMapper.listByUser(subject, 100);
    }

    /** 已核实成年判定（经 ProfileBandPort 读取生效年龄带；档案缺失按未成年 fail-closed）。 */
    private boolean isVerifiedAdult(String subject) {
        return SubscriptionPolicy.isVerifiedAdult(profileBandPort.effectiveAgeBand(subject));
    }

    private static String traceId() {
        return com.leyon.lumin.foundation.contract.ApiEnvelope.currentTraceId();
    }
}
