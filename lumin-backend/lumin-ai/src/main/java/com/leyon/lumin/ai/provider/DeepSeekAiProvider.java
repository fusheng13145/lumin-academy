package com.leyon.lumin.ai.provider;

import com.leyon.lumin.ai.config.AiProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DeepSeek 官方 API 适配器（OpenAI-compatible /chat/completions）。
 * 当前默认关闭，设置 lumin.ai.provider=deepseek 且配置 DEEPSEEK_API_KEY 后启用。
 */
@Component
public class DeepSeekAiProvider implements AiProvider {

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DeepSeekAiProvider(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(10, properties.getTimeoutSeconds())))
                .build();
    }

    @Override
    public String name() {
        return "deepseek";
    }

    @Override
    public String model() {
        return properties.getDeepseek().getModel();
    }

    @Override
    public boolean available() {
        return !properties.getDeepseek().getApiKey().isBlank();
    }

    @Override
    public AiCompletion generate(AiRequest request) {
        if (properties.getDeepseek().getApiKey().isBlank()) {
            throw new AiProviderException("DeepSeek API key 未配置，无法调用官方 API");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model());
            body.put("stream", false);
            body.put("temperature", request.temperature());
            body.put("max_tokens", request.maxTokens());
            List<Map<String, String>> messages = new ArrayList<>();
            if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
                messages.add(Map.of("role", "system", "content", request.systemPrompt()));
            }
            messages.add(Map.of("role", "user", "content", request.userPrompt()));
            body.put("messages", messages);

            String payload = objectMapper.writeValueAsString(body);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getDeepseek().getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AiProviderException("DeepSeek 返回 HTTP " + response.statusCode() + ": " + truncate(response.body()));
            }

            var root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new AiProviderException("DeepSeek 返回空内容: " + truncate(response.body()));
            }
            int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
            int completionTokens = root.path("usage").path("completion_tokens").asInt(0);
            String finishReason = root.path("choices").path(0).path("finish_reason").asText("stop");
            return new AiCompletion(content, name(), model(), promptTokens, completionTokens, finishReason);
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiProviderException("调用 DeepSeek 失败: " + exception.getMessage(), exception);
        }
    }

    private String baseUrl() {
        String value = properties.getDeepseek().getBaseUrl();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 400 ? value : value.substring(0, 400) + "...";
    }
}
