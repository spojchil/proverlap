package io.github.spojchil.proverlap.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.spojchil.proverlap.model.dto.Finding;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LLM 审查输出解析器 — 从 JSON 文本提取结构化发现列表。
 * <p>
 * 模型通过 {@code response_format: json_object} 返回结构化 JSON：
 * <pre>{@code
 * {
 *   "findings": [
 *     { "severity": "阻断", "file": "...", "line": 42, "title": "...", "description": "...", "suggestion": "..." }
 *   ],
 *   "summary": "..."
 * }
 * }</pre>
 */
@Slf4j
@Component
public class FindingParser {

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 从 LLM JSON 输出解析发现列表。
     *
     * @param text        LLM 返回的 JSON 文本
     * @param modelSource 来源模型标识（如 deepseek-v4-flash）
     * @return 结构化发现列表
     */
    public List<Finding> parse(String text, String modelSource) {
        List<Finding> findings = new ArrayList<>();
        if (text == null || text.isBlank()) return findings;

        JsonNode root;
        try {
            root = objectMapper.readTree(extractJson(text));
        } catch (JsonProcessingException e) {
            log.warn("LLM 输出 JSON 解析失败: {}", e.getMessage());
            return findings;
        }

        JsonNode findingsNode = root.path("findings");
        if (!findingsNode.isArray()) return findings;

        for (JsonNode node : findingsNode) {
            Finding f = Finding.builder()
                    .severity(node.path("severity").asText("警告"))
                    .file(node.path("file").asText(""))
                    .line(node.path("line").asInt(0))
                    .title(node.path("title").asText(""))
                    .description(node.path("description").asText(""))
                    .suggestion(node.path("suggestion").asText(""))
                    .modelSource(modelSource)
                    .build();
            findings.add(f);
        }

        return findings;
    }

    /**
     * 从 LLM 原始输出中提取 JSON 部分。
     * <p>
     * 有时 LLM 会在 JSON 外包裹 markdown 代码块（```json ... ```），
     * 提取内部的纯 JSON 文本。无 markdown 包裹时直接返回原文。
     */
    static String extractJson(String text) {
        String trimmed = text.trim();
        int start = trimmed.indexOf("```json");
        if (start >= 0) {
            int innerStart = trimmed.indexOf('\n', start) + 1;
            int end = trimmed.lastIndexOf("```");
            if (end > innerStart) {
                return trimmed.substring(innerStart, end).trim();
            }
        }
        // 尝试 ``` 无语言标记
        if (trimmed.startsWith("```")) {
            int innerStart = trimmed.indexOf('\n') + 1;
            int end = trimmed.lastIndexOf("```");
            if (end > innerStart) {
                return trimmed.substring(innerStart, end).trim();
            }
        }
        return trimmed;
    }
}
