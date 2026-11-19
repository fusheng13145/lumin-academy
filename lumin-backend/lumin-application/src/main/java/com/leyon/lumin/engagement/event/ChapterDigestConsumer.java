package com.leyon.lumin.engagement.event;

import com.leyon.lumin.engagement.domain.NotificationDigestPolicy;
import com.leyon.lumin.engagement.domain.SubscriptionPolicy;
import com.leyon.lumin.engagement.repository.NotificationDigestMapper;
import com.leyon.lumin.engagement.repository.NotificationDigestRow;
import com.leyon.lumin.engagement.repository.SubscriptionMapper;
import com.leyon.lumin.engagement.repository.SubscriptionRow;
import com.leyon.lumin.foundation.event.DomainEventSubscriber;
import com.leyon.lumin.foundation.event.InboxRecorder;
import com.leyon.lumin.foundation.port.ChapterAccessPort;
import com.leyon.lumin.foundation.port.NotificationPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

/**
 * ContentPublished.v1 追更聚合消费者（docs/13 UGC-037；07 T34 接线；04 §6.1）：
 * 仅消费 CHAPTER + isNewChapter=true（新章首指针；修订/作品重发布不触发 UPDATE）。
 * 逐接收者经 NotificationDigestPolicy 裁决：退订不推（查询域已限 enabled=1）、
 * 下架/不可读不推（ChapterAccessPort 每接收者 VisibilityPolicy 裁决，INV-03）、
 * 免打扰时段不推（接收人时区窗口）、同接收者同作品同自然日最多 1 条
 * （notification_digest U(recipient,work,local_date) 幂等去重）。
 * 推送通道为站内 UPDATE 通知；外部推送另验（UGC-037 范围登记）。
 */
@Component
public class ChapterDigestConsumer implements DomainEventSubscriber {

    private static final Logger log = LoggerFactory.getLogger(ChapterDigestConsumer.class);
    private static final String CONSUMER_NAME = "engagement-chapter-digest";

    private final InboxRecorder inboxRecorder;
    private final SubscriptionMapper subscriptionMapper;
    private final NotificationDigestMapper digestMapper;
    private final NotificationPort notificationPort;
    private final ChapterAccessPort chapterAccessPort;
    private final ObjectMapper objectMapper;

    public ChapterDigestConsumer(InboxRecorder inboxRecorder,
                                 SubscriptionMapper subscriptionMapper,
                                 NotificationDigestMapper digestMapper,
                                 NotificationPort notificationPort,
                                 ChapterAccessPort chapterAccessPort,
                                 ObjectMapper objectMapper) {
        this.inboxRecorder = inboxRecorder;
        this.subscriptionMapper = subscriptionMapper;
        this.digestMapper = digestMapper;
        this.notificationPort = notificationPort;
        this.chapterAccessPort = chapterAccessPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public String consumerName() {
        return CONSUMER_NAME;
    }

    @Override
    public Set<String> supportedEventTypes() {
        return Set.of("CONTENT_PUBLISHED_V1");
    }

    @Override
    @Transactional
    public void handle(UUID eventId, String payloadJson) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        try {
            inboxRecorder.record(CONSUMER_NAME, eventId.toString(), now);
        } catch (DuplicateKeyException e) {
            return;
        }
        JsonNode payload = objectMapper.readTree(payloadJson);
        // 只消费新章（修订/作品重发布 isNewChapter=false——T34 NOT_NEW_CHAPTER 语义）
        if (!"CHAPTER".equals(payload.get("targetType").asString())
                || !payload.get("isNewChapter").asBoolean()) {
            return;
        }
        String workId = payload.get("workId").asString();
        String chapterId = payload.get("targetId").asString();
        String eventIdText = eventId.toString();
        int sent = 0;
        int quiet = 0;
        int deduped = 0;
        for (SubscriptionRow row : subscriptionMapper.listEnabledByWork(workId)) {
            String recipient = row.userId();
            // 每接收者可见裁决（下架/无指针/等级带 → null 不推，INV-03）
            boolean workVisible = chapterAccessPort.readableVersion(recipient, chapterId) != null;
            ZoneId zone = SubscriptionPolicy.parseZoneId(row.timezoneId());
            if (zone == null) {
                zone = ZoneOffset.UTC;
            }
            Instant instant = now.toInstant(ZoneOffset.UTC);
            LocalDate localDate = LocalDate.ofInstant(instant, zone);
            LocalTime localTime = LocalTime.ofInstant(instant, zone);
            boolean quietHours = SubscriptionPolicy.isQuiet(
                    new SubscriptionPolicy.QuietWindow(row.quietStart(), row.quietEnd()),
                    localTime);
            LocalDate lastDate = digestMapper.findLastDigestDate(recipient, workId);
            NotificationDigestPolicy.Decision decision = NotificationDigestPolicy.evaluate(
                    NotificationDigestPolicy.EventType.NEW_CHAPTER, recipient, workId,
                    chapterId, localDate.toString(), true, quietHours, workVisible,
                    lastDate == null ? null : lastDate.toString());
            if (!decision.push()) {
                if (decision.reason() == NotificationDigestPolicy.DropReason.QUIET_HOURS) {
                    quiet++;
                } else if (decision.reason()
                        == NotificationDigestPolicy.DropReason.ALREADY_DIGESTED_TODAY) {
                    deduped++;
                }
                continue;
            }
            if (sendDigest(eventIdText, recipient, workId, chapterId, localDate, now)) {
                sent++;
            } else {
                deduped++;
            }
        }
        if (sent > 0 || quiet > 0 || deduped > 0) {
            log.info("ChapterDigest 扇出完成 workId={} chapterId={} sent={} quietDropped={} "
                    + "deduped={}", workId, chapterId, sent, quiet, deduped);
        }
    }

    /** 聚合落表 + 站内 UPDATE 通知（同日唯一性由 U(recipient,work,local_date) 兜底）。 */
    private boolean sendDigest(String eventId, String recipient, String workId,
                               String chapterId, LocalDate localDate, LocalDateTime now) {
        try {
            digestMapper.insert(new NotificationDigestRow(UUID.randomUUID().toString(),
                    recipient, workId, localDate, chapterId, now, now, "system", now,
                    "system", 1L));
        } catch (DuplicateKeyException e) {
            return false;
        }
        notificationPort.create(new NotificationPort.NotificationRequest(recipient,
                "UPDATE", eventId, "追更更新", "你订阅的作品发布了新章节",
                "CHAPTER", chapterId));
        return true;
    }
}
