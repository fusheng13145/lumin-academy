package com.leyon.lumin.engagement.domain;

/**
 * 追更聚合投影纯函数（07 T34 契约级；04 §6.1 P1 表
 * bookmark/subscription/notification_digest 强制预留不启用——本策略为
 * P1 订阅投影的去重设计验收，不启用任何产品入口/HTTP 端点）。
 *
 * <p>规则：仅新章事件触发 UPDATE 候选（修订/作者公告不触发）；同人同作品
 * 同自然日至多 1 条；退订不推；免打扰时段不推；作品下架/终止不推。
 * 无 IO、无时钟读取；日期与订阅状态由调用方注入。
 */
public final class NotificationDigestPolicy {

    /** 合成事件类型（P1 订阅投影输入；仅 NEW_CHAPTER 触发 UPDATE 候选）。 */
    public enum EventType { NEW_CHAPTER, REVISION, ANNOUNCEMENT }

    public enum DropReason {
        NONE, NOT_NEW_CHAPTER, UNSUBSCRIBED, QUIET_HOURS, WORK_HIDDEN, ALREADY_DIGESTED_TODAY
    }

    /** UPDATE 聚合候选（每接收者/作品/自然日至多一条）。 */
    public record Candidate(String recipientId, String workId, String chapterId,
                            String localDate) {
    }

    public record Decision(boolean push, DropReason reason, Candidate candidate) {
    }

    private NotificationDigestPolicy() {
    }

    /**
     * 追更聚合判定。
     *
     * @param lastDigestDate 同接收者同作品最近一次聚合的自然日（无记录为 null）
     */
    public static Decision evaluate(EventType eventType, String recipientId, String workId,
                                    String chapterId, String localDate, boolean subscribed,
                                    boolean quietHours, boolean workVisible,
                                    String lastDigestDate) {
        if (eventType == null || eventType != EventType.NEW_CHAPTER) {
            return notPush(DropReason.NOT_NEW_CHAPTER);
        }
        if (!subscribed) {
            return notPush(DropReason.UNSUBSCRIBED);
        }
        if (quietHours) {
            return notPush(DropReason.QUIET_HOURS);
        }
        if (!workVisible) {
            return notPush(DropReason.WORK_HIDDEN);
        }
        if (localDate != null && localDate.equals(lastDigestDate)) {
            return notPush(DropReason.ALREADY_DIGESTED_TODAY);
        }
        return new Decision(true, DropReason.NONE,
                new Candidate(recipientId, workId, chapterId, localDate));
    }

    private static Decision notPush(DropReason reason) {
        return new Decision(false, reason, null);
    }
}
