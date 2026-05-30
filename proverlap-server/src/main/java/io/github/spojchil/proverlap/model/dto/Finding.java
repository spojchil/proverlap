package io.github.spojchil.proverlap.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 审查发现 — 从 LLM 输出的 Markdown 文本解析为结构化数据。
 */
@Data
@Builder
public class Finding {

    /** 严重度：阻断 / 警告 / 建议 */
    private String severity;

    /** 文件路径 */
    private String file;

    /** 行号 */
    private int line;

    /** 问题标题 */
    private String title;

    /** 详细描述 */
    private String description;

    /** 修复建议 */
    private String suggestion;

    /** 来源模型: modelA / modelB */
    private String modelSource;

    /** 模型自身置信度（如能找到），默认 0.5 */
    @Builder.Default
    private double confidence = 0.5;
}
