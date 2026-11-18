package com.leyon.lumin.foundation.port;

import java.util.UUID;

/**
 * 审核路由配置端口（docs/13 UGC-008 配置到业务消费者闭环；03 §5.3）：
 * moderation 经本端口取 REVIEW_ROUTING ACTIVE 修订解析出的路由参数
 * （全量基线 / 灰度按案件分桶），替代硬编码常量。实现方为 configuration 域。
 */
public interface ReviewRoutingConfigPort {

    /** 案件级路由配置（灰度分桶按案件 ID 稳定命中；无 ACTIVE 基线 → 安全默认）。 */
    ReviewRoutingConfig resolveForCase(UUID caseId);

    /** 路由参数（置信下限 bp / 抽检比例 bp / 未成年保护开关）。 */
    record ReviewRoutingConfig(int minAutoConfidenceBp, int sampleRateBp,
                               boolean minorProtectionEnabled) {

        public static ReviewRoutingConfig defaults() {
            return new ReviewRoutingConfig(9800, 500, true);
        }
    }
}
