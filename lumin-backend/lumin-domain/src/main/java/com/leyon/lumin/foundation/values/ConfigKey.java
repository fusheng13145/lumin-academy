package com.leyon.lumin.foundation.values;

/**
 * 配置键白名单（04 §5.1 config_revision.config_key）。
 * ACCESS_POLICY 与 CONTENT_CATALOG 的 rollout 固定 10000、不能部分灰度（05 §4.2）。
 */
public enum ConfigKey {
    REVIEW_ROUTING,
    ACCESS_POLICY,
    NOTIFICATION_POLICY,
    CONTENT_CATALOG;

    /** 该键是否只允许全量激活（rollout 固定 10000）。 */
    public boolean requiresFullRollout() {
        return this == ACCESS_POLICY || this == CONTENT_CATALOG;
    }
}
