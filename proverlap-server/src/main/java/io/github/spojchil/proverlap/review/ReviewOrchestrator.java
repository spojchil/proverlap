package io.github.spojchil.proverlap.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.spojchil.proverlap.aggregation.ResultAggregator;
import io.github.spojchil.proverlap.config.GitHubClient;
import io.github.spojchil.proverlap.config.GitHubProperties;
import io.github.spojchil.proverlap.context.ContextBuilder;
import io.github.spojchil.proverlap.model.dto.ReviewResult;
import io.github.spojchil.proverlap.model.dto.WebhookPayload;
import io.github.spojchil.proverlap.model.enums.ReviewMode;
import io.github.spojchil.proverlap.model.enums.TierLevel;
import io.github.spojchil.proverlap.review.prompts.PrSummaryPrompt;
import io.github.spojchil.proverlap.tier.TierClassifier;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/** 审查编排器 — 多维度审查 + 双模型交叉验证 + Check Run + API 审查。 */
@Slf4j
@Service
public class ReviewOrchestrator {

    private final GitHubClient gitHubClient;
    private final TierClassifier tierClassifier;
    private final GitHubProperties gitHubProperties;
    private final ContextBuilder contextBuilder;
    private final DimensionReviewer dimensionReviewer;
    private final ResultAggregator resultAggregator;

    @Qualifier("modelA")
    private final ChatModel modelA;

    private final PrSummaryPrompt prSummaryPrompt;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ReviewOrchestrator(
            GitHubClient gitHubClient,
            TierClassifier tierClassifier,
            GitHubProperties gitHubProperties,
            ContextBuilder contextBuilder,
            DimensionReviewer dimensionReviewer,
            ResultAggregator resultAggregator,
            @Qualifier("modelA") ChatModel modelA,
            PrSummaryPrompt prSummaryPrompt) {
        this.gitHubClient = gitHubClient;
        this.tierClassifier = tierClassifier;
        this.gitHubProperties = gitHubProperties;
        this.contextBuilder = contextBuilder;
        this.dimensionReviewer = dimensionReviewer;
        this.resultAggregator = resultAggregator;
        this.modelA = modelA;
        this.prSummaryPrompt = prSummaryPrompt;
    }

    /** 异步触发审查链路（Webhook 模式）。 */
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
                checkRunId =
                        gitHubClient.createCheckRun(owner, repo, payload.getCommitSha(), instId);
            } catch (Exception e) {
                log.error("Check run 创建失败: {}", e.getMessage());
            }
        }

        try {
            String diff =
                    gitHubClient.getPullRequestDiff(owner, repo, payload.getPrNumber(), instId);
            if (diff == null || diff.isBlank()) {
                log.warn("PR diff 为空: {} #{}", payload.getFullName(), payload.getPrNumber());
                if (checkRunId != null)
                    finishCheckRun(
                            owner,
                            repo,
                            checkRunId,
                            "neutral",
                            "diff 为空",
                            "PR diff 为空，跳过审查",
                            instId);
                return;
            }

            // 并行：审查 + 变更摘要
            List<String> files = ContextBuilder.extractFiles(diff);
            CompletableFuture<ReviewOutcome> reviewFuture =
                    CompletableFuture.supplyAsync(
                            () ->
                                    doMultiDimensionReview(
                                            diff,
                                            payload.getPrTitle(),
                                            owner,
                                            repo,
                                            payload.getPrNumber(),
                                            files));
            CompletableFuture<String> summaryFuture =
                    CompletableFuture.supplyAsync(
                            () ->
                                    generateSummary(
                                            payload.getPrTitle(),
                                            payload.getPrDescription(),
                                            files));

            ReviewOutcome outcome = reviewFuture.join();
            String prSummary = "";
            try {
                prSummary = summaryFuture.join();
            } catch (Exception e) {
                log.warn("PR 摘要生成失败: {}", e.getMessage());
            }

            log.info("审查完成: {} #{}", payload.getFullName(), payload.getPrNumber());

            String comment =
                    buildComment(
                            prSummary,
                            outcome.result(),
                            payload.getFullName(),
                            payload.getPrNumber(),
                            mode);
            gitHubClient.postReview(owner, repo, payload.getPrNumber(), comment, instId);

            if (checkRunId != null) {
                boolean hasBlocking = outcome.blockingCount() > 0;
                String conclusion =
                        (mode == ReviewMode.BLOCK_ON_FINDINGS && hasBlocking)
                                ? "failure"
                                : "success";
                finishCheckRun(
                        owner,
                        repo,
                        checkRunId,
                        conclusion,
                        "审查完成 · " + (conclusion.equals("failure") ? "发现阻断问题" : "无阻断"),
                        prSummary + "\n\n---\n\n" + outcome.result(),
                        instId);
            }

        } catch (Exception e) {
            log.error("审查失败: {} #{}", payload.getFullName(), payload.getPrNumber(), e);
            String errorComment =
                    "> **PRoverlap 审查异常**\n>\n> 审查过程发生错误。\n>\n> ```\n> "
                            + e.getMessage()
                            + "\n> ```";
            gitHubClient.postReview(owner, repo, payload.getPrNumber(), errorComment, instId);
            if (checkRunId != null)
                finishCheckRun(
                        owner,
                        repo,
                        checkRunId,
                        "failure",
                        "审查异常",
                        "审查过程发生错误: " + e.getMessage(),
                        instId);
        }
    }

    /** 同步审查并返回结果文本（API 模式）。 */
    public ReviewResult reviewSync(String owner, String repo, int prNumber) {
        String diff;
        try {
            diff = gitHubClient.getPullRequestDiff(owner, repo, prNumber);
        } catch (IllegalStateException e) {
            return ReviewResult.builder()
                    .owner(owner)
                    .repo(repo)
                    .prNumber(prNumber)
                    .tier(TierLevel.TIER_1)
                    .findings("获取 PR diff 失败：\n" + e.getMessage())
                    .build();
        }

        if (diff == null || diff.isBlank()) {
            return ReviewResult.builder()
                    .owner(owner)
                    .repo(repo)
                    .prNumber(prNumber)
                    .tier(TierLevel.TIER_1)
                    .findings("(PR diff 为空)")
                    .build();
        }

        List<String> files = ContextBuilder.extractFiles(diff);
        TierLevel tier = tierClassifier.classify(countLines(diff), files);
        log.info("同步审查: {}/{} #{} → {}", owner, repo, prNumber, tier.getCode());

        ReviewOutcome outcome = doMultiDimensionReview(diff, "", owner, repo, prNumber, files);
        String prSummary = generateSummary("", "", files);

        return ReviewResult.builder()
                .owner(owner)
                .repo(repo)
                .prNumber(prNumber)
                .tier(tier)
                .findings(prSummary + "\n\n---\n\n" + outcome.result())
                .build();
    }

    /** 多维度审查 + 双模型 CV + 格式化输出 */
    private ReviewOutcome doMultiDimensionReview(
            String diff,
            String prTitle,
            String owner,
            String repo,
            int prNumber,
            List<String> files) {
        String ref = gitHubClient.getPrBranch(owner, repo, prNumber);
        String context = contextBuilder.build(owner, repo, diff, ref != null ? ref : "");

        TierLevel tier = tierClassifier.classify(countLines(diff), files);
        List<DimensionReviewer.DimensionResult> results =
                dimensionReviewer.review(prTitle != null ? prTitle : "", context, tier);

        ResultAggregator.AggregationResult aggregated = resultAggregator.aggregate(results);
        return new ReviewOutcome(aggregated.text(), aggregated.blockingCount());
    }

    private record ReviewOutcome(String result, int blockingCount) {}

    /** 生成 PR 变更摘要（单模型，轻量，30 秒超时） */
    private String generateSummary(String prTitle, String prDescription, List<String> files) {
        if (prTitle == null) prTitle = "";
        if (prDescription == null) prDescription = "";
        String fileList = files.isEmpty() ? "无" : String.join(", ", files);
        String context = "PR 标题: " + prTitle + "\nPR 描述: " + prDescription + "\n变更文件: " + fileList;

        try {
            ChatResponse response =
                    modelA.chat(
                            List.of(
                                    SystemMessage.from(prSummaryPrompt.system()),
                                    UserMessage.from(context)));
            String raw = response.aiMessage().text();
            return objectMapper.readTree(raw).path("summary").asText("");
        } catch (Exception e) {
            log.warn("PR 摘要调用失败: {}", e.getMessage());
            return "";
        }
    }

    private String buildComment(
            String prSummary, String reviewText, String fullName, int prNumber, ReviewMode mode) {
        StringBuilder sb = new StringBuilder();
        if (prSummary != null && !prSummary.isBlank()) {
            sb.append(prSummary).append("\n\n");
        }
        sb.append(extractSummary(reviewText));

        if (mode == ReviewMode.COMMENT_ONLY) {
            // 仅评论模式：Checks 不可用，附加前 3 条严重发现
            sb.append("\n\n---\n\n");
            sb.append(extractTopFindings(reviewText, 3));
        } else {
            sb.append("\n\n> 详细信息见 [Checks](https://github.com/")
                    .append(fullName)
                    .append("/pull/")
                    .append(prNumber)
                    .append("/checks) 标签页");
        }
        return sb.toString();
    }

    /** 从审查文本中提取前 N 条严重发现 */
    private static String extractTopFindings(String reviewText, int max) {
        StringBuilder sb = new StringBuilder("**主要发现**\n\n");
        int count = 0;
        for (String line : reviewText.split("\n")) {
            if (line.contains("> **阻断**") || line.contains("> **警告**")) {
                sb.append(line.trim()).append("\n");
                if (++count >= max) break;
            }
        }
        if (count == 0) {
            sb.append("未发现重大问题。\n");
        }
        return sb.toString();
    }

    private ReviewMode parseMode() {
        try {
            return ReviewMode.valueOf(gitHubProperties.getReviewMode());
        } catch (IllegalArgumentException e) {
            log.warn("无效的 ReviewMode: {}，回退为 COMMENT_ONLY", gitHubProperties.getReviewMode());
            return ReviewMode.COMMENT_ONLY;
        }
    }

    private void finishCheckRun(
            String owner,
            String repo,
            long checkRunId,
            String conclusion,
            String title,
            String summary,
            long instId) {
        try {
            gitHubClient.updateCheckRun(
                    owner, repo, checkRunId, conclusion, title, summary, instId);
        } catch (Exception e) {
            log.error("Check run 更新失败: {}", e.getMessage());
        }
    }

    private static int countLines(String diff) {
        return (int) diff.lines().count();
    }

    private static String extractSummary(String result) {
        if (result == null || result.isBlank()) return "审查完成";
        String[] lines = result.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, lines.length); i++) {
            String trimmed = lines[i].trim();
            if (!trimmed.isEmpty()) sb.append(trimmed).append("\n");
        }
        return sb.toString().trim();
    }
}
