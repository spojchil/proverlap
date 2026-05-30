package io.github.spojchil.proverlap.review;

import io.github.spojchil.proverlap.aggregation.ResultAggregator;
import io.github.spojchil.proverlap.config.GitHubClient;
import io.github.spojchil.proverlap.config.GitHubProperties;
import io.github.spojchil.proverlap.config.TierProperties;
import io.github.spojchil.proverlap.context.ContextBuilder;
import io.github.spojchil.proverlap.model.dto.ReviewResult;
import io.github.spojchil.proverlap.model.dto.WebhookPayload;
import io.github.spojchil.proverlap.model.enums.TierLevel;
import io.github.spojchil.proverlap.tier.TierClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ReviewOrchestrator 审查编排单元测试。
 */
@DisplayName("ReviewOrchestrator 审查编排单元测试")
@ExtendWith(MockitoExtension.class)
class ReviewOrchestratorTest {

    @Mock
    private GitHubClient gitHubClient;
    @Mock
    private DimensionReviewer dimensionReviewer;
    @Mock
    private ContextBuilder contextBuilder;
    @Mock
    private ResultAggregator resultAggregator;

    private TierClassifier tierClassifier;
    private GitHubProperties gitHubProperties;
    private ReviewOrchestrator orchestrator;

    private static final String OWNER = "test-owner";
    private static final String REPO = "test-repo";
    private static final int PR_NUMBER = 1;
    private static final long INSTALLATION_ID = 123L;
    private static final String DIFF = "diff --git a/Foo.java b/Foo.java\n+password = \"secret\";\n";

    @BeforeEach
    void setUp() {
        TierProperties tierProperties = new TierProperties();
        tierProperties.setT1MaxDiffLines(50);
        tierProperties.setT2MaxDiffLines(500);
        tierClassifier = spy(new TierClassifier(tierProperties));
        gitHubProperties = new GitHubProperties();
        gitHubProperties.setInstallationId(INSTALLATION_ID);
        orchestrator = new ReviewOrchestrator(
                gitHubClient, tierClassifier, gitHubProperties, contextBuilder,
                dimensionReviewer, resultAggregator);
    }

    // ==================== Webhook 异步模式 ====================

    @Test
    @DisplayName("review — 多维度审查 → 贴 Review Comment")
    void asyncMultiDimensionFlow() {
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER, INSTALLATION_ID))
                .thenReturn(DIFF);
        when(gitHubClient.getPrBranch(anyString(), anyString(), anyInt())).thenReturn("main");
        when(contextBuilder.build(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("context");
        when(dimensionReviewer.review(anyString(), anyString(), any()))
                .thenReturn(List.of(DimensionReviewer.DimensionResult.of("security", "fine", true)));
        when(resultAggregator.aggregate(any())).thenReturn("## 审查总结\nfine");

        WebhookPayload payload = buildPayload("feat: 新功能");
        orchestrator.review(payload);

        verify(gitHubClient).postReview(eq(OWNER), eq(REPO), eq(PR_NUMBER),
                contains("审查总结"), eq(INSTALLATION_ID));
        verify(dimensionReviewer).review(eq("feat: 新功能"), eq("context"), any());
    }

    @Test
    @DisplayName("review — diff 为空时跳过")
    void asyncEmptyDiff() {
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER, INSTALLATION_ID))
                .thenReturn("");

        orchestrator.review(buildPayload("fix: xxx"));

        verify(dimensionReviewer, never()).review(anyString(), anyString(), any());
        verify(gitHubClient, never()).postReview(anyString(), anyString(), anyInt(), any(), anyLong());
    }

    @Test
    @DisplayName("review — 审查异常时贴 error comment")
    void asyncErrorPostsErrorComment() {
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER, INSTALLATION_ID))
                .thenThrow(new RuntimeException("API 限流"));

        orchestrator.review(buildPayload("perf: xxx"));

        verify(gitHubClient).postReview(eq(OWNER), eq(REPO), eq(PR_NUMBER),
                contains("PRoverlap 审查异常"), eq(INSTALLATION_ID));
    }

    // ==================== API 同步模式 ====================

    @Test
    @DisplayName("reviewSync — diff 为空时返回空结果")
    void syncEmptyDiff() {
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER)).thenReturn("");

        ReviewResult result = orchestrator.reviewSync(OWNER, REPO, PR_NUMBER);

        assertEquals(TierLevel.TIER_1, result.getTier());
        assertEquals("(PR diff 为空)", result.getFindings());
    }

    @Test
    @DisplayName("reviewSync — 无标题回退 feat 全维度")
    void syncNoTitle() {
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER))
                .thenReturn(DIFF);
        when(gitHubClient.getPrBranch(anyString(), anyString(), anyInt())).thenReturn("main");
        when(contextBuilder.build(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("context");
        when(dimensionReviewer.review(anyString(), anyString(), any()))
                .thenReturn(List.of(DimensionReviewer.DimensionResult.of("security", "xd", true)));
        when(resultAggregator.aggregate(any())).thenReturn("xd");

        ReviewResult result = orchestrator.reviewSync(OWNER, REPO, PR_NUMBER);

        assertTrue(result.getFindings().contains("xd"));
        verify(dimensionReviewer).review(eq(""), eq("context"), any());
    }

    // ==================== Check Run 模式 ====================

    @Test
    @DisplayName("review — BLOCK_UNTIL_REVIEWED：创建 check run 并标记 success")
    void blockUntilReviewed() {
        gitHubProperties.setReviewMode("BLOCK_UNTIL_REVIEWED");
        when(gitHubClient.createCheckRun(eq(OWNER), eq(REPO), anyString(), eq(INSTALLATION_ID)))
                .thenReturn(1L);
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER, INSTALLATION_ID))
                .thenReturn(DIFF);
        when(gitHubClient.getPrBranch(anyString(), anyString(), anyInt())).thenReturn("main");
        when(contextBuilder.build(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("context");
        when(dimensionReviewer.review(anyString(), anyString(), any()))
                .thenReturn(List.of(DimensionReviewer.DimensionResult.of("security", "ok", true)));
        when(resultAggregator.aggregate(any())).thenReturn("ok");

        orchestrator.review(buildPayload("feat: x"));

        verify(gitHubClient).createCheckRun(eq(OWNER), eq(REPO), anyString(), eq(INSTALLATION_ID));
        verify(gitHubClient).updateCheckRun(eq(OWNER), eq(REPO), eq(1L),
                eq("success"), anyString(), anyString(), eq(INSTALLATION_ID));
    }

    @Test
    @DisplayName("review — BLOCK_ON_FINDINGS + 有阻断关键词：check run 标记 failure")
    void blockOnFindingsWithBlocking() {
        gitHubProperties.setReviewMode("BLOCK_ON_FINDINGS");
        when(gitHubClient.createCheckRun(eq(OWNER), eq(REPO), anyString(), eq(INSTALLATION_ID)))
                .thenReturn(1L);
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER, INSTALLATION_ID))
                .thenReturn(DIFF);
        when(gitHubClient.getPrBranch(anyString(), anyString(), anyInt())).thenReturn("main");
        when(contextBuilder.build(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("context");
        when(dimensionReviewer.review(anyString(), anyString(), any()))
                .thenReturn(List.of(DimensionReviewer.DimensionResult.of("security",
                        "> **阻断** 硬编码密钥", true)));
        when(resultAggregator.aggregate(any())).thenReturn("> **阻断** 硬编码密钥");

        orchestrator.review(buildPayload("fix: xxx"));

        verify(gitHubClient).updateCheckRun(eq(OWNER), eq(REPO), eq(1L),
                eq("failure"), anyString(), anyString(), eq(INSTALLATION_ID));
    }

    @Test
    @DisplayName("review — 审查异常时也标记 check run failure")
    void blockModeOnException() {
        gitHubProperties.setReviewMode("BLOCK_UNTIL_REVIEWED");
        when(gitHubClient.createCheckRun(eq(OWNER), eq(REPO), anyString(), eq(INSTALLATION_ID)))
                .thenReturn(1L);
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER, INSTALLATION_ID))
                .thenThrow(new RuntimeException("API 超时"));

        orchestrator.review(buildPayload("perf: xxx"));

        verify(gitHubClient).updateCheckRun(eq(OWNER), eq(REPO), eq(1L),
                eq("failure"), anyString(), anyString(), eq(INSTALLATION_ID));
    }

    // ==================== API 模式边界 ====================

    @Test
    @DisplayName("reviewSync — 未配置认证时返回配置指引")
    void syncNoAuthReturnsGuide() {
        gitHubProperties.setInstallationId(null);
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER))
                .thenThrow(new IllegalStateException("未配置 GitHub 认证..."));

        ReviewResult result = orchestrator.reviewSync(OWNER, REPO, PR_NUMBER);

        assertTrue(result.getFindings().contains("未配置 GitHub 认证"),
                "应返回配置指引: " + result.getFindings());
    }

    // ==================== 工具方法 ====================

    private WebhookPayload buildPayload(String prTitle) {
        return WebhookPayload.builder()
                .action("opened")
                .fullName(OWNER + "/" + REPO)
                .prNumber(PR_NUMBER)
                .installationId(INSTALLATION_ID)
                .commitSha("abc123def456")
                .prTitle(prTitle)
                .build();
    }
}
