package io.github.spojchil.proverlap.review;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.spojchil.proverlap.config.GitHubClient;
import io.github.spojchil.proverlap.config.GitHubProperties;
import io.github.spojchil.proverlap.config.ModelProperties;
import io.github.spojchil.proverlap.context.ContextBuilder;
import io.github.spojchil.proverlap.model.dto.CrossValidationResult;
import io.github.spojchil.proverlap.model.dto.Finding;
import io.github.spojchil.proverlap.model.dto.ReviewResult;
import io.github.spojchil.proverlap.model.dto.WebhookPayload;
import io.github.spojchil.proverlap.model.enums.ReviewMode;
import io.github.spojchil.proverlap.model.enums.TierLevel;
import io.github.spojchil.proverlap.review.prompts.CrossValidationCommentFormatter;
import io.github.spojchil.proverlap.review.prompts.SecurityPrompt;
import io.github.spojchil.proverlap.tier.TierClassifier;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 审查编排器 — 双模型交叉验证 + Check Run + API 审查。
 * <p>
 * 两个不同模型独立审查同一段代码，CrossValidator 比对发现，标记共识/分歧/单模型。
 */
@Slf4j
@Service
public class ReviewOrchestrator {

    private final GitHubClient gitHubClient;
    @Qualifier("modelA")
    private final ChatModel modelA;
    @Qualifier("modelB")
    private final ChatModel modelB;
    private final SecurityPrompt securityPrompt;
    private final TierClassifier tierClassifier;
    private final GitHubProperties gitHubProperties;
    private final ModelProperties modelProperties;
    private final ContextBuilder contextBuilder;
    private final FindingParser findingParser;
    private final CrossValidator crossValidator;
    private final CrossValidationCommentFormatter commentFormatter;
    @Qualifier("reviewExecutor")
    private final Executor reviewExecutor;

    public ReviewOrchestrator(GitHubClient gitHubClient,
                              @Qualifier("modelA") ChatModel modelA,
                              @Qualifier("modelB") ChatModel modelB,
                              SecurityPrompt securityPrompt,
                              TierClassifier tierClassifier,
                              GitHubProperties gitHubProperties,
                              ModelProperties modelProperties,
                              ContextBuilder contextBuilder,
                              FindingParser findingParser,
                              CrossValidator crossValidator,
                              CrossValidationCommentFormatter commentFormatter,
                              @Qualifier("reviewExecutor") Executor reviewExecutor) {
        this.gitHubClient = gitHubClient;
        this.modelA = modelA;
        this.modelB = modelB;
        this.securityPrompt = securityPrompt;
        this.tierClassifier = tierClassifier;
        this.gitHubProperties = gitHubProperties;
        this.modelProperties = modelProperties;
        this.contextBuilder = contextBuilder;
        this.findingParser = findingParser;
        this.crossValidator = crossValidator;
        this.commentFormatter = commentFormatter;
        this.reviewExecutor = reviewExecutor;
    }

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
        long instId = payload.getInstallationId();

        ReviewMode mode = parseMode();
        Long checkRunId = null;

        if (mode != ReviewMode.COMMENT_ONLY && payload.getCommitSha() != null) {
            try {
                checkRunId = gitHubClient.createCheckRun(owner, repo, payload.getCommitSha(), instId);
            } catch (Exception e) {
                log.error("Check run 创建失败: {}", e.getMessage());
            }
        }

        try {
            String diff = gitHubClient.getPullRequestDiff(owner, repo, payload.getPrNumber(), instId);
            if (diff == null || diff.isBlank()) {
                log.warn("PR diff 为空: {} #{}", payload.getFullName(), payload.getPrNumber());
                if (checkRunId != null) finishCheckRun(owner, repo, checkRunId, "neutral", "diff 为空", "PR diff 为空，跳过审查", instId);
                return;
            }

            String result = doDualModelReview(diff, owner, repo, payload.getPrNumber());
            log.info("审查完成: {} #{}", payload.getFullName(), payload.getPrNumber());
            gitHubClient.postReview(owner, repo, payload.getPrNumber(), result, instId);

            if (checkRunId != null) {
                String conclusion = determineConclusion(mode, result);
                finishCheckRun(owner, repo, checkRunId, conclusion,
                        "审查完成 · " + (conclusion.equals("failure") ? "发现阻断问题" : "无阻断"),
                        "PRoverlap 安全审查完成，详见 Review Comment", instId);
            }

        } catch (Exception e) {
            log.error("审查失败: {} #{}", payload.getFullName(), payload.getPrNumber(), e);
            String errorComment = "> **PRoverlap 审查异常**\n>\n> 审查过程发生错误。\n>\n> ```\n> " + e.getMessage() + "\n> ```";
            gitHubClient.postReview(owner, repo, payload.getPrNumber(), errorComment, instId);
            if (checkRunId != null) finishCheckRun(owner, repo, checkRunId, "failure", "审查异常", "审查过程发生错误: " + e.getMessage(), instId);
        }
    }

    /**
     * 同步审查并返回结果文本（API 模式）。
     */
    public ReviewResult reviewSync(String owner, String repo, int prNumber) {
        String diff;
        try {
            diff = gitHubClient.getPullRequestDiff(owner, repo, prNumber);
        } catch (IllegalStateException e) {
            return ReviewResult.builder()
                    .owner(owner).repo(repo).prNumber(prNumber)
                    .tier(TierLevel.TIER_1)
                    .findings("获取 PR diff 失败：\n" + e.getMessage())
                    .build();
        }

        if (diff == null || diff.isBlank()) {
            return ReviewResult.builder()
                    .owner(owner).repo(repo).prNumber(prNumber)
                    .tier(TierLevel.TIER_1)
                    .findings("(PR diff 为空)")
                    .build();
        }

        TierLevel tier = tierClassifier.classify(countLines(diff), ContextBuilder.extractFiles(diff));
        log.info("同步审查: {}/{} #{} → {}", owner, repo, prNumber, tier.getCode());

        String findings = doDualModelReview(diff, owner, repo, prNumber);
        return ReviewResult.builder()
                .owner(owner).repo(repo).prNumber(prNumber)
                .tier(tier)
                .findings(findings)
                .build();
    }

    /** 双模型并行审查 + 交叉比对 + 格式化输出 */
    private String doDualModelReview(String diff, String owner, String repo, int prNumber) {
        String ref = gitHubClient.getPrBranch(owner, repo, prNumber);
        String context = contextBuilder.build(owner, repo, diff, ref != null ? ref : "");

        // 并行调用两个模型
        CompletableFuture<String> futureA = CompletableFuture.supplyAsync(
                () -> callModel(modelA, context), reviewExecutor);
        CompletableFuture<String> futureB = CompletableFuture.supplyAsync(
                () -> callModel(modelB, context), reviewExecutor);

        String textA, textB;
        try {
            textA = futureA.get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("模型 A 调用失败", e);
            textA = "模型 A 调用失败: " + e.getMessage();
        }
        try {
            textB = futureB.get(120, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("模型 B 调用失败", e);
            textB = "模型 B 调用失败: " + e.getMessage();
        }

        // 解析两个模型的输出
        String modelAName = modelProperties.getModelA().getModelName();
        String modelBName = modelProperties.getModelB().getModelName();
        List<Finding> findingsA = findingParser.parse(textA, modelAName);
        List<Finding> findingsB = findingParser.parse(textB, modelBName);

        // 交叉比对
        CrossValidationResult cross = crossValidator.compare(findingsA, findingsB);

        // 格式化输出
        return commentFormatter.format(cross);
    }

    /** 调用单个 LLM 执行审查 */
    private String callModel(ChatModel model, String context) {
        ChatResponse response = model.chat(List.of(
                SystemMessage.from(securityPrompt.system()),
                UserMessage.from(context)));
        return response.aiMessage().text();
    }

    private ReviewMode parseMode() {
        try {
            return ReviewMode.valueOf(gitHubProperties.getReviewMode());
        } catch (IllegalArgumentException e) {
            log.warn("无效的 ReviewMode: {}，回退为 COMMENT_ONLY", gitHubProperties.getReviewMode());
            return ReviewMode.COMMENT_ONLY;
        }
    }

    private String determineConclusion(ReviewMode mode, String formattedOutput) {
        if (mode == ReviewMode.BLOCK_ON_FINDINGS
                && formattedOutput != null && formattedOutput.contains("**阻断**")) {
            return "failure";
        }
        return "success";
    }

    private void finishCheckRun(String owner, String repo, long checkRunId, String conclusion,
                                 String title, String summary, long instId) {
        try {
            gitHubClient.updateCheckRun(owner, repo, checkRunId, conclusion, title,
                    summary, instId);
        } catch (Exception e) {
            log.error("Check run 更新失败: {}", e.getMessage());
        }
    }

    /** diff 行数估算 */
    private static int countLines(String diff) {
        return (int) diff.lines().count();
    }
}
