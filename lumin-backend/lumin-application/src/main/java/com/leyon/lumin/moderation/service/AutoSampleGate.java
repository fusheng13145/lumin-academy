package com.leyon.lumin.moderation.service;

import org.springframework.stereotype.Component;

/**
 * 自动决定抽检闸（03 §5.3：自动决定抽检 5%）。默认实现委托
 * {@link com.leyon.lumin.moderation.domain.QualitySampler}（HMAC 稳定采样）；
 * 测试可注入确定性闸（命中/未命中）验证两条链路。
 */
public interface AutoSampleGate {

    /** true = 该自动决定被抽中复核（转人工队列）。 */
    boolean sampled(String caseId);
}
