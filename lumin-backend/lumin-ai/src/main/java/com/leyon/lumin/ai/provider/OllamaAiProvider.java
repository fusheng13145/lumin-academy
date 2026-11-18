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
 * 本地 Ollama 适配器。默认模型 deepseek-r1:8b，调用 /api/chat（非流式）。
 * 该实现不依赖 Spring AI，避免为单一供应商引入额外抽象层。
 */
@Component
public class OllamaAiProvider implements AiProvider {

    private final AiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OllamaAiProvider(AiProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(10, properties.getTimeoutSeconds())))
                .build();
    }

    @Override
    public String name() {
        return "ollama";
    }

    @Override
    public String model() {
        return properties.getOllama().getModel();
    }

    @Override
    public boolean available() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/tags"))
                    .timeout(Duration.ofSeconds(3))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception exception) {
            return false;
        }
    }

    @Override
    public AiCompletion generate(AiRequest request) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", model());
            body.put("stream", false);
            List<Map<String, String>> messages = new ArrayList<>();
            if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
                messages.add(Map.of("role", "system", "content", request.systemPrompt()));
            }
            messages.add(Map.of("role", "user", "content", request.userPrompt()));
            body.put("messages", messages);
            body.put("options", Map.of(
                    "temperature", request.temperature(),
                    "num_predict", request.maxTokens()
            ));

            String payload = objectMapper.writeValueAsString(body);
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/chat"))
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AiProviderException("Ollama 返回 HTTP " + response.statusCode() + ": " + truncate(response.body()));
            }

            var root = objectMapper.readTree(response.body());
            String content = root.path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new AiProviderException("Ollama 返回空内容: " + truncate(response.body()));
            }
            int promptTokens = root.path("prompt_eval_count").asInt(0);
            int completionTokens = root.path("eval_count").asInt(0);
            return new AiCompletion(content, name(), model(), promptTokens, completionTokens, "stop");
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiProviderException("调用 Ollama 失败: " + exception.getMessage(), exception);
        }
    }

    private String baseUrl() {
        String value = properties.getOllama().getBaseUrl();
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 400 ? value : value.substring(0, 400) + "...";
    }
}
