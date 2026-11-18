package com.leyon.lumin.ai.provider;

import java.util.List;

/** AI 供应商端口：Ollama、DeepSeek 官方 API 以及后续模型都实现此接口。 */
public interface AiProvider {

    String name();

    String model();

    boolean available();

    AiCompletion generate(AiRequest request);

    /** 多轮对话扩展；默认退化到单轮，供应商可覆盖。 */
    default AiCompletion chat(List<AiMessage> messages, double temperature, int maxTokens) {
        StringBuilder user = new StringBuilder();
        String system = null;
        for (AiMessage message : messages) {
            if ("system".equals(message.role())) {
                system = message.content();
            } else {
                user.append(message.content()).append('\n');
            }
        }
        return generate(new AiRequest(system, user.toString(), temperature, maxTokens));
    }

    record AiMessage(String role, String content) {
    }
}
