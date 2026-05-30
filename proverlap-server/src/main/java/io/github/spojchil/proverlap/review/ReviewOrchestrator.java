package io.github.spojchil.proverlap.review;

import io.github.spojchil.proverlap.aggregation.ResultAggregator;
import io.github.spojchil.proverlap.config.GitHubClient;
import io.github.spojchil.proverlap.config.GitHubProperties;
import io.github.spojchil.proverlap.context.ContextBuilder;
import io.github.spojchil.proverlap.model.dto.ReviewResult;
import io.github.spojchil.proverlap.model.dto.WebhookPayload;
import io.github.spojchil.proverlap.model.enums.ReviewMode;
import io.github.spojchil.proverlap.model.enums.TierLevel;
import io.github.spojchil.proverlap.tier.TierClassifier;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 审查编排器 — 多维度审查 + 双模型交叉验证 + Check Run + API 审查。
 * <p>
 * PR 标题驱动维度激活（feat→5维, fix→2维），DimensionReviewer 按 Tier 调度模型。
 */
@Slf4j
@Service
public class ReviewOrchestrator {

    private final GitHubClient gitHubClient;
    private final TierClassifier tierClassifier;
    private final GitHubProperties gitHubProperties;
    private final ContextBuilder contextBuilder;
    private final DimensionReviewer dimensionReviewer;
    private final ResultAggregator resultAggregator;

    public ReviewOrchestrator(GitHubClient gitHubClient,
                              TierClassifier tierClassifier,
                              GitHubProperties gitHubProperties,
                              ContextBuilder contextBuilder,
                              DimensionReviewer dimensionReviewer,
                              ResultAggregator resultAggregator) {
        this.gitHubClient = gitHubClient;
        this.tierClassifier = tierClassifier;
        this.gitHubProperties = gitHubProperties;
        this.contextBuilder = contextBuilder;
        this.dimensionReviewer = dimensionReviewer;
        this.resultAggregator = resultAggregator;
    }

    /**
     * 异步触发审查链路（Webhook 模式）。
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
                if (checkRunId != null) finishCheckRun(owner, repo, checkRunId, "neutral",
                        "diff 为空", "PR diff 为空，跳过审查", instId);
                return;
            }

            String result = doMultiDimensionReview(diff, payload.getPrTitle(),
                    owner, repo, payload.getPrNumber());
            log.info("审查完成: {} #{}", payload.getFullName(), payload.getPrNumber());

            // 提取摘要行作为评论，完整报告放入 Check Run
            String summary = extractSummary(result);
            String comment = summary
                    + "\n\n> 详细信息见 [Checks](https://github.com/" + payload.getFullName()
                    + "/pull/" + payload.getPrNumber() + "/checks) 标签页";
            gitHubClient.postReview(owner, repo, payload.getPrNumber(), comment, instId);

            if (checkRunId != null) {
                String conclusion = determineConclusion(mode, result);
                finishCheckRun(owner, repo, checkRunId, conclusion,
                        "审查完成 · " + (conclusion.equals("failure") ? "发现阻断问题" : "无阻断"),
                        result, instId);
            }

        } catch (Exception e) {
            log.error("审查失败: {} #{}", payload.getFullName(), payload.getPrNumber(), e);
            String errorComment = "> **PRoverlap 审查异常**\n>\n> 审查过程发生错误。\n>\n> ```\n> " + e.getMessage() + "\n> ```";
            gitHubClient.postReview(owner, repo, payload.getPrNumber(), errorComment, instId);
            if (checkRunId != null) finishCheckRun(owner, repo, checkRunId, "failure",
                    "审查异常", "审查过程发生错误: " + e.getMessage(), instId);
        }
    }

    /**
     * 同步审查并返回结果文本（API 模式）。
     * <p>
     * API 模式下无 PR 标题，默认走"feat"全维度审查。
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

        String findings = doMultiDimensionReview(diff, "", owner, repo, prNumber);
        return ReviewResult.builder()
                .owner(owner).repo(repo).prNumber(prNumber)
                .tier(tier)
                .findings(findings)
                .build();
    }

    /** 多维度审查 + 双模型 CV + 格式化输出 */
    private String doMultiDimensionReview(String diff, String prTitle,
                                           String owner, String repo, int prNumber) {
        String ref = gitHubClient.getPrBranch(owner, repo, prNumber);
        String context = contextBuilder.build(owner, repo, diff, ref != null ? ref : "");

        TierLevel tier = tierClassifier.classify(countLines(diff), ContextBuilder.extractFiles(diff));
        List<DimensionReviewer.DimensionResult> results =
                dimensionReviewer.review(prTitle != null ? prTitle : "", context, tier);

        return resultAggregator.aggregate(results);
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
        if (mode == ReviewMode.BLOCK_ON_FINDINGS && formattedOutput != null) {
            // 从审查总结中解析阻断数："0 阻断 · 1 警告 · 2 建议"
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("(\\d+) 阻断").matcher(formattedOutput);
            if (m.find() && Integer.parseInt(m.group(1)) > 0) {
                return "failure";
            }
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

    private static int countLines(String diff) {
        return (int) diff.lines().count();
    }

    /** 从聚合结果中提取摘要（前 3 行） */
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

