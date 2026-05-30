package io.github.spojchil.proverlap.review;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.spojchil.proverlap.config.GitHubClient;
import io.github.spojchil.proverlap.config.GitHubProperties;
import io.github.spojchil.proverlap.config.ModelProperties;
import io.github.spojchil.proverlap.config.TierProperties;
import io.github.spojchil.proverlap.context.ContextBuilder;
import io.github.spojchil.proverlap.model.dto.CrossValidationResult;
import io.github.spojchil.proverlap.model.dto.ReviewResult;
import io.github.spojchil.proverlap.model.dto.WebhookPayload;
import io.github.spojchil.proverlap.model.enums.TierLevel;
import io.github.spojchil.proverlap.review.prompts.CrossValidationCommentFormatter;
import io.github.spojchil.proverlap.review.prompts.SecurityPrompt;
import io.github.spojchil.proverlap.tier.TierClassifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ReviewOrchestrator 双模型审查编排单元测试。
 */
@DisplayName("ReviewOrchestrator 双模型审查编排单元测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewOrchestratorTest {

    @Mock private GitHubClient gitHubClient;
    @Mock private ChatModel modelA;
    @Mock private ChatModel modelB;
    @Mock private FindingParser findingParser;
    @Mock private CrossValidator crossValidator;
    @Mock private CrossValidationCommentFormatter commentFormatter;
    @Mock private ContextBuilder contextBuilder;

    private SecurityPrompt securityPrompt;
    private TierClassifier tierClassifier;
    private GitHubProperties gitHubProperties;
    private ModelProperties modelProperties;
    private ReviewOrchestrator orchestrator;
    private final Executor reviewExecutor = Runnable::run; // 同步执行，便于测试

    private static final String OWNER = "test-owner";
    private static final String REPO = "test-repo";
    private static final int PR_NUMBER = 1;
    private static final long INSTALLATION_ID = 123L;
    private static final String DIFF = "diff --git a/Foo.java b/Foo.java\n+password = \"secret\";\n";
    private static final String CONTEXT = "## 上下文\n...";

    @BeforeEach
    void setUp() {
        securityPrompt = new SecurityPrompt();
        TierProperties tierProperties = new TierProperties();
        tierProperties.setT1MaxDiffLines(50);
        tierProperties.setT2MaxDiffLines(500);
        tierClassifier = spy(new TierClassifier(tierProperties));
        gitHubProperties = new GitHubProperties();
        gitHubProperties.setInstallationId(INSTALLATION_ID);
        modelProperties = new ModelProperties();
        modelProperties.setModelA(new ModelProperties.ModelConfig());
        modelProperties.getModelA().setModelName("DeepSeek");
        modelProperties.setModelB(new ModelProperties.ModelConfig());
        modelProperties.getModelB().setModelName("mimo");

        orchestrator = new ReviewOrchestrator(gitHubClient, modelA, modelB,
                securityPrompt, tierClassifier, gitHubProperties, modelProperties,
                contextBuilder, findingParser, crossValidator, commentFormatter, reviewExecutor);
    }

    // ==================== 异步模式 ====================

    @Test
    @DisplayName("review — 异步双模型并行审查 → 交叉比对 → 发评论")
    void asyncDualModelFlow() {
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER, INSTALLATION_ID))
                .thenReturn(DIFF);
        when(gitHubClient.getPrBranch(OWNER, REPO, PR_NUMBER)).thenReturn("main");
        when(contextBuilder.build(eq(OWNER), eq(REPO), eq(DIFF), eq("main"))).thenReturn(CONTEXT);
        when(modelA.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("> **阻断** `Foo.java` L2 — 问题A")).build());
        when(modelB.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("未发现安全问题。")).build());
        when(findingParser.parse(contains("问题A"), eq("DeepSeek")))
                .thenReturn(List.of(io.github.spojchil.proverlap.model.dto.Finding.builder()
                        .severity("阻断").file("Foo.java").line(2).title("问题A").modelSource("DeepSeek").build()));
        when(findingParser.parse(eq("未发现安全问题。"), eq("mimo")))
                .thenReturn(List.of());
        when(crossValidator.compare(anyList(), anyList()))
                .thenReturn(CrossValidationResult.builder()
                        .consensus(List.of()).divergences(List.of())
                        .modelAOnly(List.of(io.github.spojchil.proverlap.model.dto.Finding.builder().build()))
                        .modelBOnly(List.of()).build());
        when(commentFormatter.format(any())).thenReturn("## 审查结果\nA发现");

        orchestrator.review(buildPayload());

        verify(modelA).chat(anyList());
        verify(modelB).chat(anyList());
        verify(crossValidator).compare(anyList(), anyList());
        verify(gitHubClient).postReview(eq(OWNER), eq(REPO), eq(PR_NUMBER),
                eq("## 审查结果\nA发现"), eq(INSTALLATION_ID));
    }

    // ==================== API 同步模式 ====================

    @Test
    @DisplayName("reviewSync — 双模型审查返回格式化结果")
    void syncDualModelFlow() {
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER))
                .thenReturn(DIFF);
        when(gitHubClient.getPrBranch(OWNER, REPO, PR_NUMBER)).thenReturn("main");
        when(contextBuilder.build(any(), any(), any(), eq("main"))).thenReturn(CONTEXT);
        when(modelA.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("未发现")).build());
        when(modelB.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("未发现")).build());
        when(findingParser.parse(anyString(), anyString())).thenReturn(List.of());
        when(crossValidator.compare(anyList(), anyList()))
                .thenReturn(CrossValidationResult.builder().build());
        when(commentFormatter.format(any())).thenReturn("## 审查结果\n未发现");

        ReviewResult result = orchestrator.reviewSync(OWNER, REPO, PR_NUMBER);

        assertNotNull(result.getFindings());
        assertEquals("## 审查结果\n未发现", result.getFindings());
    }

    @Test
    @DisplayName("reviewSync — diff 为空时返回空结果")
    void syncEmptyDiff() {
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER)).thenReturn("");

        ReviewResult result = orchestrator.reviewSync(OWNER, REPO, PR_NUMBER);

        assertEquals(TierLevel.TIER_1, result.getTier());
        assertEquals("(PR diff 为空)", result.getFindings());
    }

    // ==================== 工具方法 ====================

    // ==================== 异步模式边界 ====================

    @Test
    @DisplayName("review — diff 为空时跳过审查")
    void asyncEmptyDiff() {
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER, INSTALLATION_ID))
                .thenReturn("");

        orchestrator.review(buildPayload());

        verify(modelA, never()).chat(anyList());
        verify(modelB, never()).chat(anyList());
        verify(gitHubClient, never()).postReview(anyString(), anyString(), anyInt(), any(), anyLong());
    }

    @Test
    @DisplayName("review — 模型 A 异常时仍发 error comment")
    void asyncModelAError() {
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER, INSTALLATION_ID))
                .thenReturn(DIFF);
        when(gitHubClient.getPrBranch(OWNER, REPO, PR_NUMBER)).thenReturn("main");
        when(contextBuilder.build(any(), any(), any(), eq("main"))).thenReturn(CONTEXT);
        when(modelA.chat(anyList())).thenThrow(new RuntimeException("模型 A 超时"));
        when(modelB.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("未发现")).build());
        when(findingParser.parse(contains("模型 A 调用失败"), eq("DeepSeek")))
                .thenReturn(List.of());
        when(findingParser.parse(eq("未发现"), eq("mimo")))
                .thenReturn(List.of());
        when(crossValidator.compare(anyList(), anyList()))
                .thenReturn(CrossValidationResult.builder().consensus(List.of()).divergences(List.of())
                        .modelAOnly(List.of()).modelBOnly(List.of()).build());
        when(commentFormatter.format(any())).thenReturn("## 审查结果\n未发现");

        orchestrator.review(buildPayload());

        verify(gitHubClient).postReview(eq(OWNER), eq(REPO), eq(PR_NUMBER),
                eq("## 审查结果\n未发现"), eq(INSTALLATION_ID));
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
        when(gitHubClient.getPrBranch(OWNER, REPO, PR_NUMBER)).thenReturn("main");
        when(contextBuilder.build(any(), any(), any(), eq("main"))).thenReturn(CONTEXT);
        when(modelA.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("未发现")).build());
        when(modelB.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("未发现")).build());
        when(findingParser.parse(anyString(), anyString())).thenReturn(List.of());
        when(crossValidator.compare(anyList(), anyList()))
                .thenReturn(CrossValidationResult.builder().consensus(List.of()).divergences(List.of())
                        .modelAOnly(List.of()).modelBOnly(List.of()).build());
        when(commentFormatter.format(any())).thenReturn("## 审查结果\n未发现");

        orchestrator.review(buildPayload());

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
        when(gitHubClient.getPrBranch(OWNER, REPO, PR_NUMBER)).thenReturn("main");
        when(contextBuilder.build(any(), any(), any(), eq("main"))).thenReturn(CONTEXT);
        when(modelA.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("> **阻断** 硬编码密钥")).build());
        when(modelB.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("未发现")).build());
        when(findingParser.parse(anyString(), eq("DeepSeek")))
                .thenReturn(List.of(io.github.spojchil.proverlap.model.dto.Finding.builder()
                        .severity("阻断").file("Foo.java").line(2).title("硬编码密钥").modelSource("DeepSeek").build()));
        when(findingParser.parse(anyString(), eq("mimo"))).thenReturn(List.of());
        when(crossValidator.compare(anyList(), anyList()))
                .thenReturn(CrossValidationResult.builder().consensus(List.of()).divergences(List.of())
                        .modelAOnly(List.of(io.github.spojchil.proverlap.model.dto.Finding.builder()
                                .severity("阻断").file("Foo.java").line(2).title("硬编码密钥").build()))
                        .modelBOnly(List.of()).build());
        when(commentFormatter.format(any())).thenReturn("## 审查结果\n**阻断**");

        orchestrator.review(buildPayload());

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

        orchestrator.review(buildPayload());

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

    private WebhookPayload buildPayload() {
        return WebhookPayload.builder()
                .action("opened")
                .fullName(OWNER + "/" + REPO)
                .prNumber(PR_NUMBER)
                .installationId(INSTALLATION_ID)
                .commitSha("abc123def456")
                .build();
    }
}
