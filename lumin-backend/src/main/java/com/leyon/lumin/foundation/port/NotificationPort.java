package com.leyon.lumin.foundation.port;

/**
 * 站内通知端口（02 §3.1：foundation 声明端口，engagement 提供适配器）。
 * 裁决/治理等终局事实经此触达作者与相关方（02 §5.2 NotificationCreated.v1 的
 * 消费者作用；U(recipient,source_event,category) 幂等，重复消费不重复通知）。
 */
public interface NotificationPort {

    /**
     * 创建站内通知（模板文案由调用方生成，不让模型自述成功——03 §5.1 N6）。
     *
     * @return 通知 ID（source_event_id+category 冲突时返回已有 ID，幂等）
     */
    String create(NotificationRequest request);

    /** 通知请求（category 白名单由适配器按 04 §5.1 CHECK 校验）。 */
    record NotificationRequest(String recipientId, String category, String sourceEventId,
                               String title, String body, String resourceType,
                               String resourceId) {
    }
}
