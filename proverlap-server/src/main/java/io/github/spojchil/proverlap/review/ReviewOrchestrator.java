package io.github.spojchil.proverlap.review;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.spojchil.proverlap.config.GitHubClient;
import io.github.spojchil.proverlap.config.GitHubProperties;
import io.github.spojchil.proverlap.model.dto.ReviewResult;
import io.github.spojchil.proverlap.model.dto.WebhookPayload;
import io.github.spojchil.proverlap.model.enums.TierLevel;
import io.github.spojchil.proverlap.review.prompts.SecurityPrompt;
import io.github.spojchil.proverlap.tier.TierClassifier;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 审查编排器 — 串联拉 diff、调 LLM、发评论的整条链路。
 * <p>
 * 支持两种触发模式：
 * <ul>
 *   <li>Webhook 异步模式：接收 WebhookPayload，异步审查并贴 Review Comment</li>
 *   <li>API 同步模式：传入 owner/repo/pr，同步返回审查结果文本</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewOrchestrator {

    private final GitHubClient gitHubClient;
    @Qualifier("modelA")
    private final ChatModel modelA;
    private final SecurityPrompt securityPrompt;
    private final TierClassifier tierClassifier;
    private final GitHubProperties gitHubProperties;

    /**
     * 异步触发审查链路（Webhook 模式）。
     * <p>
     * 使用 {@code reviewExecutor} 虚拟线程池执行，审查结果贴为 GitHub Review Comment。
     */
    @Async("reviewExecutor")
    public void review(WebhookPayload payload) {
        String[] parts = payload.getFullName().split("/");
        String owner = parts[0];
        String repo = parts[1];

        try {
            String diff = gitHubClient.getPullRequestDiff(
                    owner, repo, payload.getPrNumber(), payload.getInstallationId());

            if (diff == null || diff.isBlank()) {
                log.warn("PR diff 为空: {} #{}", payload.getFullName(), payload.getPrNumber());
                return;
            }

            String result = doReview(diff);
            log.info("审查完成: {} #{}", payload.getFullName(), payload.getPrNumber());
            gitHubClient.postReview(owner, repo, payload.getPrNumber(),
                    result, payload.getInstallationId());

        } catch (Exception e) {
            log.error("审查失败: {} #{}", payload.getFullName(), payload.getPrNumber(), e);
            String errorComment = "> **PRoverlap 审查异常**\n>\n> 审查过程发生错误。\n>\n> ```\n> " + e.getMessage() + "\n> ```";
            gitHubClient.postReview(owner, repo, payload.getPrNumber(),
                    errorComment, payload.getInstallationId());
        }
    }

    /**
     * 同步审查并返回结果文本（API 模式）。
     * <p>
     * 不走 Review Comment，直接返回 LLM 原始输出和 Tier 信息。
     *
     * @param owner    仓库所有者
     * @param repo     仓库名
     * @param prNumber PR 编号
     * @return 审查结果（含 Tier 分级和 LLM 输出）
     */
    public ReviewResult reviewSync(String owner, String repo, int prNumber) {
        Long installationId = gitHubProperties.getInstallationId();
        if (installationId == null) {
            throw new IllegalStateException("API 模式需要配置 GITHUB_INSTALLATION_ID");
        }

        String diff = gitHubClient.getPullRequestDiff(owner, repo, prNumber, installationId);
        if (diff == null || diff.isBlank()) {
            return ReviewResult.builder()
                    .owner(owner).repo(repo).prNumber(prNumber)
                    .tier(TierLevel.TIER_1)
                    .findings("(PR diff 为空)")
                    .build();
        }

        TierLevel tier = tierClassifier.classify(countLines(diff), extractFiles(diff));
        log.info("同步审查: {}/{} #{} → {}", owner, repo, prNumber, tier.getCode());

        String findings = doReview(diff);
        return ReviewResult.builder()
                .owner(owner).repo(repo).prNumber(prNumber)
                .tier(tier)
                .findings(findings)
                .build();
    }

    /** 调用 LLM 执行审查，返回原始输出 */
    private String doReview(String diff) {
        ChatResponse response = modelA.chat(List.of(
                SystemMessage.from(securityPrompt.system()),
                UserMessage.from("以下是 PR 的代码变更 (unified diff):\n\n```diff\n" + diff + "\n```")));
        return response.aiMessage().text();
    }

    /** 从 diff 文本中提取变更文件列表 */
    private static List<String> extractFiles(String diff) {
        List<String> files = new ArrayList<>();
        for (String line : diff.split("\n")) {
            if (line.startsWith("+++ b/")) {
                files.add(line.substring(6));
            }
        }
        return files;
    }

    /** diff 行数估算 */
    private static int countLines(String diff) {
        return (int) diff.lines().count();
    }
}
