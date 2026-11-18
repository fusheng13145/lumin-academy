package com.leyon.lumin.foundation.web;

/**
 * 同步变更回执（05 §4.4 Receipt）：字段 id、resourceType、state、revision；
 * 「成功」仅指本次请求语义（202 不得解释为审核通过）。
 */
public record ReceiptView(String id, String resourceType, String state, long revision) {
}
