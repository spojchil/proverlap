package io.github.spojchil.proverlap.review;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import io.github.spojchil.proverlap.config.GitHubClient;
import io.github.spojchil.proverlap.model.dto.WebhookPayload;
import io.github.spojchil.proverlap.review.prompts.SecurityPrompt;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 审查编排器 — 串联拉 diff、调 LLM、发评论的整条链路。
 * <p>
 * 从 WebhookPayload 驱动，异步执行审查流程，Webhook 接口立刻返回 200。
 * 当前阶段只做安全维度 + 模型 A 单模型审查。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewOrchestrator {

    private final GitHubClient gitHubClient;
    @Qualifier("modelA")
    private final ChatModel modelA;
    private final SecurityPrompt securityPrompt;

    /**
     * 异步触发审查链路。
     * <p>
     * 使用 {@code reviewExecutor} 虚拟线程池执行，Webhook 无需等待审查完成。
     *
     * @param payload Webhook 解析后的 PR 关键信息
     */
    @Async("reviewExecutor")
    public void review(WebhookPayload payload) {
        log.info("开始审查: {} #{}", payload.getFullName(), payload.getPrNumber());

        String[] parts = payload.getFullName().split("/");
        String owner = parts[0];
        String repo = parts[1];

        try {
            // 1. 拉取 PR diff
            String diff = gitHubClient.getPullRequestDiff(
                    owner, repo, payload.getPrNumber(), payload.getInstallationId());

            if (diff == null || diff.isBlank()) {
                log.warn("PR diff 为空: {} #{}", payload.getFullName(), payload.getPrNumber());
                return;
            }

            // 2. 调用 LLM 审查
            ChatResponse response = modelA.chat(List.of(
                    SystemMessage.from(securityPrompt.system()),
                    UserMessage.from("以下是 PR 的代码变更 (unified diff):\n\n```diff\n" + diff + "\n```")));

            String result = response.aiMessage().text();
            log.info("审查完成: {} #{}", payload.getFullName(), payload.getPrNumber());

            // 3. 发布 Review Comment
            gitHubClient.postReview(owner, repo, payload.getPrNumber(),
                    result, payload.getInstallationId());

        } catch (Exception e) {
            log.error("审查失败: {} #{}", payload.getFullName(), payload.getPrNumber(), e);
            // 兜底：贴一条 error comment 告知审查失败
            String errorComment = "> **PRoverlap 审查异常**\n>\n> 审查过程发生错误，请检查日志。\n>\n> ```\n> " + e.getMessage() + "\n> ```";
            gitHubClient.postReview(owner, repo, payload.getPrNumber(),
                    errorComment, payload.getInstallationId());
        }
    }
}
