package io.github.spojchil.proverlap.review;

import io.github.spojchil.proverlap.model.dto.Finding;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * LLM 审查输出解析器 — 从 Markdown 文本提取结构化发现列表。
 * <p>
 * 解析 SecurityPrompt 的输出格式：
 * <pre>
 * > **阻断** `src/AuthService.java` L3 — 硬编码密钥
 * > 详细描述...
 * > 建议: 修复方案...
 * </pre>
 */
@Slf4j
@Component
public class FindingParser {

    /** 匹配发现标题行: > **严重度** `文件路径` L行号 — 标题 */
    private static final Pattern FINDING_HEADER = Pattern.compile(
            "^>\\s*\\*\\*(阻断|警告|建议)\\*\\*\\s*`([^`]+)`\\s*L(\\d+)\\s*[—\\-]\\s*(.*)");

    /** 匹配建议行 */
    private static final Pattern SUGGESTION_LINE = Pattern.compile(
            "^>\\s*(建议|修复建议)[：:]\\s*(.*)");

    /**
     * 从 LLM 审查输出的 Markdown 文本中解析发现列表。
     *
     * @param text        LLM 原始输出文本
     * @param modelSource 来源模型标识（modelA / modelB）
     * @return 结构化发现列表
     */
    public List<Finding> parse(String text, String modelSource) {
        List<Finding> findings = new ArrayList<>();
        if (text == null || text.isBlank()) return findings;

        String[] lines = text.split("\n");
        Finding current = null;
        StringBuilder descBuilder = new StringBuilder();
        String suggestion = null;

        for (String line : lines) {
            Matcher headerMatcher = FINDING_HEADER.matcher(line);
            if (headerMatcher.matches()) {
                // 保存上一个 Finding
                if (current != null) {
                    current.setDescription(descBuilder.toString().trim());
                    current.setSuggestion(suggestion);
                    findings.add(current);
                }

                current = Finding.builder()
                        .severity(headerMatcher.group(1))
                        .file(headerMatcher.group(2))
                        .line(Integer.parseInt(headerMatcher.group(3)))
                        .title(headerMatcher.group(4))
                        .modelSource(modelSource)
                        .build();
                descBuilder = new StringBuilder();
                suggestion = null;
                continue;
            }

            if (current == null) continue;

            Matcher suggestionMatcher = SUGGESTION_LINE.matcher(line);
            if (suggestionMatcher.matches()) {
                suggestion = suggestionMatcher.group(2);
                continue;
            }

            // 以 > 开头的行 → 描述/建议的一部分
            if (line.matches("^>.*")) {
                String content = line.replaceFirst("^>\\s?", "").trim();
                if (!content.isEmpty()) {
                    // 无前缀：视作描述（当前无 suggestion 匹配时）
                    descBuilder.append(content).append(" ");
                }
            }
        }

        // 保存最后一个
        if (current != null) {
            current.setDescription(descBuilder.toString().trim());
            current.setSuggestion(suggestion);
            findings.add(current);
        }

        return findings;
    }
}
