package com.leyon.lumin.foundation.port;

/**
 * 审核案件端口（02 §3.1：foundation 声明端口接口，moderation 提供适配器）。
 * 提交编排（publication）在事件核验通过后经本端口建案，不直接访问
 * moderation Repository。
 */
public interface ReviewCasePort {

    /**
     * 为通过核验的提交创建 QUEUED 案件（source=SUBMISSION，stage=1；
     * 风险/优先级初值 LOW/P2，机审报告到达后按 N4 路由更新）。
     */
    String createSubmissionCase(SubmissionCaseRequest request);

    /** 建案请求（字段来自已核验的版本与流程权威记录）。 */
    record SubmissionCaseRequest(String versionId, String workflowId, String workId,
                                 String chapterId, long policyVersion) {
    }

    /** 举报建案请求（目标未发布前 version/workflow 为空；04 §5 review_case）。 */
    record ReportCaseRequest(String workId, String chapterId, long policyVersion) {
    }

    /**
     * 举报建案（source=REPORT；目标可为准发布内容）。
     */
    String createReportCase(ReportCaseRequest request);
}
