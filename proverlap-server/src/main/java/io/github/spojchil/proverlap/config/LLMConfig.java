package io.github.spojchil.proverlap.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LangChain4j LLM 模型配置。
 * <p>
 * 创建两个 ChatModel Bean（modelA / modelB），分别对应模型插槽 A 和 B。
 * 使用 OpenAI 兼容接口，所有兼容该格式的模型均可接入（DeepSeek、智谱、Claude via API Gateway 等）。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class LLMConfig {

    private final ModelProperties modelProperties;

    /** 启动时输出已配置的模型信息 */
    @PostConstruct
    public void init() {
        log.info("LLM 模型配置初始化: 模型 A = {} ({})",
                modelProperties.getModelA().getModelName(), modelProperties.getModelA().getBaseUrl());
        log.info("LLM 模型配置初始化: 模型 B = {} ({})",
                modelProperties.getModelB().getModelName(), modelProperties.getModelB().getBaseUrl());
    }

    /**
     * 模型 A — 主审查模型。
     * <p>
     * 承担代码逻辑、安全、架构维度审查。温度 0.3 保证输出确定性。
     */
    @Bean("modelA")
    public ChatModel modelA() {
        ModelProperties.ModelConfig cfg = modelProperties.getModelA();
        return OpenAiChatModel.builder()
                .baseUrl(cfg.getBaseUrl())
                .apiKey(cfg.getApiKey())
                .modelName(cfg.getModelName())
                .temperature(modelProperties.getTemperature())
                .maxTokens(modelProperties.getMaxTokens())
                .timeout(Duration.ofSeconds(modelProperties.getTimeoutSeconds()))
                .build();
    }

    /**
     * 模型 B — 交叉验证模型。
     * <p>
     * 承担安全、逻辑维度的交叉验证。温度 0.3 保证与模型 A 可比对。
     */
    @Bean("modelB")
    public ChatModel modelB() {
        ModelProperties.ModelConfig cfg = modelProperties.getModelB();
        return OpenAiChatModel.builder()
                .baseUrl(cfg.getBaseUrl())
                .apiKey(cfg.getApiKey())
                .modelName(cfg.getModelName())
                .temperature(modelProperties.getTemperature())
                .maxTokens(modelProperties.getMaxTokens())
                .timeout(Duration.ofSeconds(modelProperties.getTimeoutSeconds()))
                .build();
    }
}
