package io.github.spojchil.proverlap.review.prompts;

/**
 * 审查维度 Prompt 统一接口。
 *
 * <p>每个审查维度实现此接口，返回 System Prompt 文本。 DimensionReviewer 通过此接口统一调度不同维度。
 */
@FunctionalInterface
public interface ReviewPrompt {
    String system();
}
