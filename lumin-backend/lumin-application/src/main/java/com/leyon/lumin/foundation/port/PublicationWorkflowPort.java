package com.leyon.lumin.foundation.port;

/**
 * 发布流程端口（02 §3.1：foundation 声明端口接口，publication 提供适配器）。
 * 人审路由（ROUTE_HUMAN_REVIEW）经本端口驱动状态机迁移；终局裁决经
 * ReviewResolved.v1 事件由 publication 消费者执行（N5），不跨模块直调。
 */
public interface PublicationWorkflowPort {

    /** 路由人工审核（AUTO_REVIEW →(ROUTE_HUMAN_REVIEW)→ HUMAN_REVIEW，03 §2.3）。 */
    String routeToHumanReview(String workflowId, long policyVersion);
}
