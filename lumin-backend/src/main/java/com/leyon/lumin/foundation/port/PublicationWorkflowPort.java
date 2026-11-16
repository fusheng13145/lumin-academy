package com.leyon.lumin.foundation.port;

/**
 * 发布流程端口（02 §3.1：foundation 声明端口接口，publication 提供适配器）。
 * moderation 的机审路由结论（N4/N5）经本端口驱动核心域状态机与发布事务，
 * 不直接访问 publication/authoring Repository（Agent 与案件域均不写公开指针）。
 */
public interface PublicationWorkflowPort {

    /**
     * 自动裁决执行（N5：核心域状态机执行）：AUTO_PASS → 发布事务
     * （版本/流程 APPROVED + 原子交换公开指针 + ContentPublished.v1；
     * 章节父作品无已通过版本则停留 HUMAN_REVIEW，03 §2.3）；
     * AUTO_REVISE → 版本/流程 REJECTED。
     *
     * @return 迁移后的 workflow 状态名（APPROVED / REJECTED / HUMAN_REVIEW）
     */
    String executeAutoDecision(String workflowId, String versionId, String decision,
                               String grade, long policyVersion);

    /** 路由人工审核（AUTO_REVIEW →(ROUTE_HUMAN_REVIEW)→ HUMAN_REVIEW，03 §2.3）。 */
    String routeToHumanReview(String workflowId, long policyVersion);
}
