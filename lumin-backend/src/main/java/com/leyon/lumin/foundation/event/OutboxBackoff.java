package com.leyon.lumin.foundation.event;

import java.time.Duration;
import java.time.Instant;

/**
 * Outbox 投递退避策略（03 §4.3）：失败退避 1/5/30 秒，此后每 60 秒至 24 小时；
 * 超 24 小时进入人工处置，**不删除事件**。delivered_at 由投递确认后写入（持久化层）。
 */
public final class OutboxBackoff {

    public static final int FIRST_DELAY_SECONDS = 1;
    public static final int SECOND_DELAY_SECONDS = 5;
    public static final int THIRD_DELAY_SECONDS = 30;
    public static final int SUBSEQUENT_DELAY_SECONDS = 60;
    /** 严格超过该时长后停止自动重试，转人工处置（03 §4.3「超24小时」）。 */
    public static final Duration MANUAL_HANDOFF_AFTER = Duration.ofHours(24);

    private OutboxBackoff() {
    }

    /**
     * 第 {@code failedAttempts} 次投递失败后的下次重试等待秒数。
     * failedAttempts 计已发生的失败次数（≥1）；非正数按 1 处理（防御性钳制）。
     */
    public static int nextRetryDelaySeconds(int failedAttempts) {
        int attempts = Math.max(1, failedAttempts);
        return switch (attempts) {
            case 1 -> FIRST_DELAY_SECONDS;
            case 2 -> SECOND_DELAY_SECONDS;
            case 3 -> THIRD_DELAY_SECONDS;
            default -> SUBSEQUENT_DELAY_SECONDS;
        };
    }

    /**
     * 是否应停止自动重试、转人工处置：自首次失败起经过时长**严格大于** 24h。
     * 输入缺失按 fail-closed 处理（true，转人工），不静默继续重试。
     */
    public static boolean needsManualHandoff(Instant firstFailureAt, Instant now) {
        if (firstFailureAt == null || now == null) {
            return true;
        }
        return Duration.between(firstFailureAt, now).compareTo(MANUAL_HANDOFF_AFTER) > 0;
    }
}
