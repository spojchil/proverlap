package io.github.spojchil.proverlap.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 异步任务执行器配置。
 *
 * <p>启用 Spring {@code @Async} 支持，使用 Java 21 虚拟线程执行并行 LLM 调用。 每任务一个虚拟线程，I/O 阻塞时自动让出平台线程。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /**
     * 审查编排虚拟线程执行器。
     *
     * <p>用于 ReviewOrchestrator 中多模型并行调用，配合 CompletableFuture 超时控制。
     */
    @Bean("reviewExecutor")
    public Executor reviewExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
