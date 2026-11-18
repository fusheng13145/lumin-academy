package com.leyon.lumin.ai.workflow;

import com.leyon.lumin.ai.domain.MemoryFact;
import com.leyon.lumin.ai.domain.MemoryKind;
import com.leyon.lumin.ai.domain.MemoryStatus;
import com.leyon.lumin.ai.provider.AiProviderException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 把模型返回的记忆 JSON 转成候选事实。只接受数组或 {"facts":[...]}；
 * 解析失败不写库，由 workflow 记录原始输出并继续产出正文。
 */
@Component
public class AiMemoryParser {

    private final ObjectMapper objectMapper;

    public AiMemoryParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<MemoryFact> parse(String raw, String workId) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String json = extractJson(raw);
        if (json.isBlank()) {
            throw new AiProviderException("记忆抽取结果未找到 JSON 数组");
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode array = root.isArray() ? root : root.path("facts");
            if (!array.isArray()) {
                throw new AiProviderException("记忆抽取结果不是数组");
            }
            List<MemoryFact> facts = new ArrayList<>();
            for (JsonNode item : array) {
                String content = item.path("content").asText("").trim();
                String subjectKey = item.path("subjectKey").asText("").trim();
                if (content.isBlank() || subjectKey.isBlank()) {
                    continue;
                }
                MemoryKind kind = parseKind(item.path("kind").asText(""));
                double confidence = item.path("confidence").asDouble(0.7);
                confidence = Math.max(0.0, Math.min(1.0, confidence));
                Instant now = Instant.now();
                facts.add(new MemoryFact(
                        UUID.randomUUID().toString(),
                        workId,
                        kind,
                        subjectKey,
                        content,
                        MemoryStatus.CANDIDATE,
                        confidence,
                        null,
                        now,
                        now
                ));
            }
            return facts;
        } catch (AiProviderException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiProviderException("记忆 JSON 解析失败: " + exception.getMessage(), exception);
        }
    }

    private static MemoryKind parseKind(String value) {
        try {
            return MemoryKind.valueOf(value.trim().toUpperCase());
        } catch (Exception exception) {
            return MemoryKind.PLOT;
        }
    }

    private static String extractJson(String raw) {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1).trim();
        }
        int objectStart = raw.indexOf('{');
        int objectEnd = raw.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            return raw.substring(objectStart, objectEnd + 1).trim();
        }
        return "";
    }
}
