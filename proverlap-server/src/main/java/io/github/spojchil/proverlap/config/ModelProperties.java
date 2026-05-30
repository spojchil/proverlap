package io.github.spojchil.proverlap.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * LLM 模型配置属性。
 * <p>
 * 两个可插拔模型插槽 A 和 B，通过环境变量注入 base-url、api-key、model-name，
 * 可对接任何兼容 OpenAI 接口格式的模型（DeepSeek、智谱、Claude via API Gateway 等）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "proverlap.models")
public class ModelProperties {

    /** 模型 A — 主审查模型（如 DeepSeek / 智谱 / Qwen） */
    private ModelConfig modelA = new ModelConfig();

    /** 模型 B — 交叉验证模型（如 Claude / GPT / Gemini） */
    private ModelConfig modelB = new ModelConfig();

    /** 最大输出 token 数，默认 16384 */
    private int maxTokens = 16384;

    @Data
    public static class ModelConfig {
        private String baseUrl;
        private String apiKey;
        private String modelName;
    }
}
