package io.github.spojchil.proverlap.review;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.spojchil.proverlap.config.GitHubClient;
import io.github.spojchil.proverlap.config.GitHubProperties;
import io.github.spojchil.proverlap.config.TierProperties;
import io.github.spojchil.proverlap.context.ContextBuilder;
import io.github.spojchil.proverlap.model.dto.ReviewResult;
import io.github.spojchil.proverlap.model.dto.WebhookPayload;
import io.github.spojchil.proverlap.model.enums.TierLevel;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * ReviewOrchestrator 审查编排单元测试。
 */
@DisplayName("ReviewOrchestrator 审查编排单元测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewOrchestratorTest {

    @Mock
    private GitHubClient gitHubClient;
    @Mock
    private ChatModel modelA;
    @Mock
    private ContextBuilder contextBuilder;

    private SecurityPrompt securityPrompt;
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
        securityPrompt = new SecurityPrompt();
        TierProperties tierProperties = new TierProperties();
        tierProperties.setT1MaxDiffLines(50);
        tierProperties.setT2MaxDiffLines(500);
        tierClassifier = spy(new TierClassifier(tierProperties));
        gitHubProperties = new GitHubProperties();
        gitHubProperties.setInstallationId(INSTALLATION_ID);
        orchestrator = new ReviewOrchestrator(gitHubClient, modelA, securityPrompt, tierClassifier, gitHubProperties, contextBuilder);
        when(contextBuilder.build(anyString(), anyString(), anyString())).thenReturn(DIFF);
    }

    // ==================== Webhook 异步模式 ====================

    @Test
    @DisplayName("review — 异步模式：拉 diff → LLM 审查 → 发评论")
    void asyncNormalFlow() {
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER, INSTALLATION_ID))
                .thenReturn(DIFF);
        doReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("发现安全隐患"))
                        .build())
                .when(modelA).chat(anyList());

        orchestrator.review(buildPayload());

        verify(gitHubClient).postReview(eq(OWNER), eq(REPO), eq(PR_NUMBER),
                eq("发现安全隐患"), eq(INSTALLATION_ID));
    }

    @Test
    @DisplayName("review — 异步模式：diff 为空时跳过")
    void asyncEmptyDiff() {
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER, INSTALLATION_ID))
                .thenReturn("");

        orchestrator.review(buildPayload());

        verify(modelA, never()).chat(anyList());
        verify(gitHubClient, never()).postReview(anyString(), anyString(), anyInt(), any(), anyLong());
    }

    @Test
    @DisplayName("review — 异步模式：LLM 异常时贴 error comment")
    void asyncLlmErrorPostsErrorComment() {
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER, INSTALLATION_ID))
                .thenReturn(DIFF);
        doThrow(new RuntimeException("LLM 超时"))
                .when(modelA).chat(anyList());

        orchestrator.review(buildPayload());

        verify(gitHubClient).postReview(eq(OWNER), eq(REPO), eq(PR_NUMBER),
                contains("PRoverlap 审查异常"), eq(INSTALLATION_ID));
    }

    // ==================== API 同步模式 ====================

    @Test
    @DisplayName("reviewSync — 同步审查返回 ReviewResult")
    void syncNormalFlow() {
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER))
                .thenReturn(DIFF);
        doReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("发现安全隐患"))
                        .build())
                .when(modelA).chat(anyList());

        ReviewResult result = orchestrator.reviewSync(OWNER, REPO, PR_NUMBER);

        assertEquals(OWNER, result.getOwner());
        assertEquals(REPO, result.getRepo());
        assertEquals(PR_NUMBER, result.getPrNumber());
        assertEquals("发现安全隐患", result.getFindings());
        verify(gitHubClient, never()).postReview(anyString(), anyString(), anyInt(), any(), anyLong());
    }

    @Test
    @DisplayName("reviewSync — diff 为空时返回空结果")
    void syncEmptyDiff() {
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER))
                .thenReturn("");

        ReviewResult result = orchestrator.reviewSync(OWNER, REPO, PR_NUMBER);

        assertEquals(TierLevel.TIER_1, result.getTier());
        assertEquals("(PR diff 为空)", result.getFindings());
    }

    @Test
    @DisplayName("reviewSync — 未配置任何认证时返回配置指引")
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
                .build();
    }
}
