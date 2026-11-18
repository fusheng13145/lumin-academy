package com.leyon.lumin.engagement.domain;

import com.leyon.lumin.engagement.domain.NotificationDigestPolicy.Candidate;
import com.leyon.lumin.engagement.domain.NotificationDigestPolicy.Decision;
import com.leyon.lumin.engagement.domain.NotificationDigestPolicy.DropReason;
import com.leyon.lumin.engagement.domain.NotificationDigestPolicy.EventType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T34 追更聚合投影契约测试（07 T34；04 §6.1 P1 强制预留不启用——
 * 仅去重设计验收，不启用产品入口）：合成新章/修订/公告事件——
 * 仅新章触发 UPDATE 候选；同人同作品同日 1 条；退订/免打扰/下架不推。
 */
class NotificationDigestPolicyTest {

    private static Decision evaluate(EventType type, String lastDigestDate) {
        return NotificationDigestPolicy.evaluate(type, "recipient-1", "work-1", "ch-1",
                "2026-11-18", true, false, true, lastDigestDate);
    }

    @Test
    void onlyNewChapterEventsProduceCandidates() {
        Decision chapter = evaluate(EventType.NEW_CHAPTER, null);
        assertThat(chapter.push()).isTrue();
        assertThat(chapter.candidate()).isEqualTo(
                new Candidate("recipient-1", "work-1", "ch-1", "2026-11-18"));
        // 修订与作者公告不触发 UPDATE 候选
        assertThat(evaluate(EventType.REVISION, null).push()).isFalse();
        assertThat(evaluate(EventType.REVISION, null).reason()).isEqualTo(DropReason.NOT_NEW_CHAPTER);
        assertThat(evaluate(EventType.ANNOUNCEMENT, null).push()).isFalse();
        assertThat(evaluate(EventType.ANNOUNCEMENT, null).reason())
                .isEqualTo(DropReason.NOT_NEW_CHAPTER);
    }

    @Test
    void sameRecipientSameWorkSameDayProducesAtMostOneDigest() {
        assertThat(evaluate(EventType.NEW_CHAPTER, null).push()).isTrue();
        // 同日第二条新章事件 → 已聚合（1 条/日）
        Decision second = evaluate(EventType.NEW_CHAPTER, "2026-11-18");
        assertThat(second.push()).isFalse();
        assertThat(second.reason()).isEqualTo(DropReason.ALREADY_DIGESTED_TODAY);
        // 次日新章 → 新候选
        Decision nextDay = NotificationDigestPolicy.evaluate(EventType.NEW_CHAPTER,
                "recipient-1", "work-1", "ch-2", "2026-11-19", true, false, true,
                "2026-11-18");
        assertThat(nextDay.push()).isTrue();
        assertThat(nextDay.candidate().chapterId()).isEqualTo("ch-2");
    }

    @Test
    void unsubscribedQuietAndHiddenWorksAreNotPushed() {
        // 退订不推
        Decision unsubscribed = NotificationDigestPolicy.evaluate(EventType.NEW_CHAPTER,
                "recipient-1", "work-1", "ch-1", "2026-11-18", false, false, true, null);
        assertThat(unsubscribed.push()).isFalse();
        assertThat(unsubscribed.reason()).isEqualTo(DropReason.UNSUBSCRIBED);
        // 免打扰时段不推
        Decision quiet = NotificationDigestPolicy.evaluate(EventType.NEW_CHAPTER,
                "recipient-1", "work-1", "ch-1", "2026-11-18", true, true, true, null);
        assertThat(quiet.push()).isFalse();
        assertThat(quiet.reason()).isEqualTo(DropReason.QUIET_HOURS);
        // 作品下架/终止不推
        Decision hidden = NotificationDigestPolicy.evaluate(EventType.NEW_CHAPTER,
                "recipient-1", "work-1", "ch-1", "2026-11-18", true, false, false, null);
        assertThat(hidden.push()).isFalse();
        assertThat(hidden.reason()).isEqualTo(DropReason.WORK_HIDDEN);
    }
}
