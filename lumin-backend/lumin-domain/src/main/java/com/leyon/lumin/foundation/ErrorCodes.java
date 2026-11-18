package com.leyon.lumin.foundation;

/**
 * 错误码字典（05 §5 全集 14 条：OK + 13 个错误码）。
 *
 * <p>工程裁决：Java 要求 public 顶层类型名与文件名一致，故本文件以
 * {@code ErrorCodes} 为顶层容器，{@code ErrorCode} 为其嵌套枚举；
 * 引用路径即 B.3.3 约定的 {@code foundation.ErrorCodes.ErrorCode}。
 * 禁止在业务源码中散落错误码字符串字面量。
 */
public final class ErrorCodes {

    private ErrorCodes() {
    }

    /** 05 §5 稳定文案与 HTTP 状态，一一对应契约 ErrorResponse.code 枚举。 */
    public enum ErrorCode {
        OK(200, "成功/已受理，语义由操作决定"),
        P1001(400, "请求参数不符合要求"),
        P1002(428, "请先获取最新版本再操作"),
        A0001(401, "请重新登录"),
        A2001(403, "当前账号无权执行此操作"),
        A2002(403, "当前年龄或监护状态不允许此操作"),
        A2003(429, "操作过于频繁，请稍后重试"),
        B2001(409, "当前状态不支持此操作"),
        B2002(412, "内容已更新，请合并后重试"),
        B2003(409, "同一请求标识不能用于不同内容"),
        B2004(404, "内容不存在或不可访问"),
        B2005(409, "此内容不能恢复传播"),
        D2001(503, "服务暂不可用，请稍后查看处理状态"),
        D2002(503, "内容状态待核对，暂不可访问");

        private final int httpStatus;
        private final String message;

        ErrorCode(int httpStatus, String message) {
            this.httpStatus = httpStatus;
            this.message = message;
        }

        public int httpStatus() {
            return httpStatus;
        }

        public String message() {
            return message;
        }
    }
}
