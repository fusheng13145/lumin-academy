package com.leyon.lumin.configuration.domain;

import com.leyon.lumin.foundation.ErrorCodes.ErrorCode;
import com.leyon.lumin.foundation.StableBuckets;
import com.leyon.lumin.foundation.values.ConfigKey;

import java.util.UUID;

/**
 * 配置灰度稳定分桶与激活校验（03 §2.4 config 灰度 / 05 §4.2；T30–T32 决策面）。
 *
 * <p>双槽模型：同 config_key 保留唯一 ACTIVE 全量基线 + 至多一条 ACTIVE 灰度版本
 * （rollout 1–9999）；按 HMAC(固定策略种子, 主体ID) 分桶，未命中使用全量基线；
 * 每次决策只绑定一个实际命中版本，不将两个版本字段拼接。
 * 0 比例不得激活；10000 激活同时取代原全量及灰度（全量槽语义）；
 * ACCESS_POLICY / CONTENT_CATALOG 固定 10000、不能部分灰度（05 §4.2）；
 * 无全量基线不能启用灰度；灰度比例不可原地改，扩量需新审批提案。
 */
public final class GrayRolloutPolicy {

    /** 灰度槽允许的万分比范围（03 §2.4：rollout 为 1 至 9999）。 */
    public static final int MIN_GRAY_BASIS_POINTS = 1;
    public static final int MAX_GRAY_BASIS_POINTS = 9999;
    /** 全量槽万分比（10000 激活同时取代原全量及灰度）。 */
    public static final int FULL_ROLLOUT_BASIS_POINTS = 10000;

    private GrayRolloutPolicy() {
    }

    /** 双槽快照：全量基线 revision（缺全量基线时为 null）+ 可空灰度版本。 */
    public record Slots(Long fullBaselineRevision, Long grayRevision,
                        Integer grayBasisPoints) {
    }

    /**
     * 激活校验：返回 {@link ErrorCode#OK} 表示该 rollout 可以落槽，否则为拒绝错误码。
     *
     * @param key            配置键（决定是否只允许全量）
     * @param basisPoints    本次激活的 rollout 万分比（1–10000）
     * @param hasFullBaseline 激活灰度时必须已有 ACTIVE 全量基线
     */
    public static ErrorCode validateActivation(ConfigKey key, Integer basisPoints,
                                               boolean hasFullBaseline) {
        if (key == null || basisPoints == null) {
            return ErrorCode.P1001;
        }
        if (basisPoints < MIN_GRAY_BASIS_POINTS || basisPoints > FULL_ROLLOUT_BASIS_POINTS) {
            // 0 比例不得激活；超 10000 非法
            return ErrorCode.P1001;
        }
        if (basisPoints == FULL_ROLLOUT_BASIS_POINTS) {
            return ErrorCode.OK;
        }
        if (key.requiresFullRollout()) {
            // 访问保护与分类配置只允许 10000，不能部分灰度（03 §2.4 / 05 §4.2）
            return ErrorCode.P1001;
        }
        return hasFullBaseline ? ErrorCode.OK : ErrorCode.P1001;
    }

    /**
     * 决策分桶选择：命中灰度桶（bucket &lt; grayBasisPoints）返回灰度版本，
     * 否则返回全量基线；只返回一个版本。
     *
     * <p>fail-closed：seed 缺失（bucket = -1）与未命中同样走全量基线——
     * 全量基线是已审批的稳定基线，不得在密钥故障时把主体暴露给未经验证的灰度槽。
     */
    public static Long select(byte[] policySeed, UUID subjectId, Slots slots) {
        if (slots == null || slots.fullBaselineRevision() == null) {
            return null;
        }
        Long grayRevision = slots.grayRevision();
        Integer grayBasisPoints = slots.grayBasisPoints();
        if (grayRevision == null || grayBasisPoints == null
                || grayBasisPoints < MIN_GRAY_BASIS_POINTS) {
            return slots.fullBaselineRevision();
        }
        int bucket = StableBuckets.bucket(policySeed, subjectId);
        if (bucket >= 0 && bucket < grayBasisPoints) {
            return grayRevision;
        }
        return slots.fullBaselineRevision();
    }
}
