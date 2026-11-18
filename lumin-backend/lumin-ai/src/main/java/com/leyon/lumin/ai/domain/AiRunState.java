package com.leyon.lumin.ai.domain;

/** 一次 AI 运行的生命周期；候选产出后进入 WAITING_AUTHOR，等待作者确认后才写入正文。 */
public enum AiRunState {
    QUEUED,
    RUNNING,
    WAITING_AUTHOR,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
