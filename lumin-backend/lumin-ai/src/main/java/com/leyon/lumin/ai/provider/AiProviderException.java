package com.leyon.lumin.ai.provider;

/** 供应商不可用、超时、返回体异常等统一运行时异常；不吞掉具体原因。 */
public class AiProviderException extends RuntimeException {

    public AiProviderException(String message) {
        super(message);
    }

    public AiProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
