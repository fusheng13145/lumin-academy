package com.leyon.lumin.engagement.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;

import java.time.Instant;

/**
 * 阅读进度边界校验（04 §5.1 reading_progress / 05 §3.4 saveProgress；T29）。
 *
 * <p>position_basis_points ∈ [0, 10000]；read_seconds ≥ 0；客户端时间不得超前
 * 服务器超过 5 分钟（04 §5.1「服务端拒绝未来超过 5 分钟时间」）。
 * 版本绑定（V2 不能错套 V1 位置）与主体归属由服务层校验（05 §3.4），不在本类范围。
 */
public final class ReadingProgressValidator {

    public static final int MIN_POSITION_BASIS_POINTS = 0;
    public static final int MAX_POSITION_BASIS_POINTS = 10000;
    /** 允许的客户端时钟最大超前量：300 秒（5 分钟）。 */
    public static final long MAX_FUTURE_CLOCK_SKEW_SECONDS = 300;

    private ReadingProgressValidator() {
    }

    /** 返回 {@link ErrorCode#OK} 表示合法，否则为拒绝错误码（400/P1001）。 */
    public static ErrorCode validate(Integer positionBasisPoints, Integer readSeconds,
                                     Instant clientUpdatedAt, Instant serverNow) {
        if (positionBasisPoints == null
                || positionBasisPoints < MIN_POSITION_BASIS_POINTS
                || positionBasisPoints > MAX_POSITION_BASIS_POINTS) {
            return ErrorCode.P1001;
        }
        if (readSeconds == null || readSeconds < 0) {
            return ErrorCode.P1001;
        }
        if (clientUpdatedAt == null || serverNow == null) {
            return ErrorCode.P1001;
        }
        if (clientUpdatedAt.isAfter(serverNow.plusSeconds(MAX_FUTURE_CLOCK_SKEW_SECONDS))) {
            return ErrorCode.P1001;
        }
        return ErrorCode.OK;
    }
}
