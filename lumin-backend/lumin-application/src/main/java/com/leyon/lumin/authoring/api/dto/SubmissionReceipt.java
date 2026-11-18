package com.leyon.lumin.authoring.api.dto;

/**
 * 提交回执（契约 SubmissionReceipt / 05 §4.4）：202 仅表示受理，
 * 客户端轮询 GET workflow 读取最终态，不得显示「发布成功」。
 */
public record SubmissionReceipt(String workflowId, String versionId, String state) {
}
