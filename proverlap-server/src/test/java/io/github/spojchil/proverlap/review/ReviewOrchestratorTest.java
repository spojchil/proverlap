package io.github.spojchil.proverlap.review;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.spojchil.proverlap.config.GitHubClient;
import io.github.spojchil.proverlap.model.dto.WebhookPayload;
import io.github.spojchil.proverlap.review.prompts.SecurityPrompt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
    private ChatModel modelA;

    private SecurityPrompt securityPrompt;
    private ReviewOrchestrator orchestrator;

    private static final String OWNER = "test-owner";
    private static final String REPO = "test-repo";
    private static final int PR_NUMBER = 1;
    private static final long INSTALLATION_ID = 123L;
    private static final String DIFF = "diff --git a/Foo.java b/Foo.java\n+password = \"secret\";";

    @BeforeEach
    void setUp() {
        securityPrompt = new SecurityPrompt();
        orchestrator = new ReviewOrchestrator(gitHubClient, modelA, securityPrompt);
    }

    // ==================== 正常流程 ====================

    @Test
    @DisplayName("review — 正常流程：拉 diff → LLM 审查 → 发评论")
    void normalFlow() {
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER, INSTALLATION_ID))
                .thenReturn(DIFF);
        doReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from("发现安全隐患"))
                        .build())
                .when(modelA).chat(anyList());

        WebhookPayload payload = buildPayload();
        orchestrator.review(payload);

        verify(gitHubClient).getPullRequestDiff(OWNER, REPO, PR_NUMBER, INSTALLATION_ID);
        verify(modelA).chat(anyList());
        verify(gitHubClient).postReview(eq(OWNER), eq(REPO), eq(PR_NUMBER),
                eq("发现安全隐患"), eq(INSTALLATION_ID));
    }

    // ==================== 边界条件 ====================

    @Test
    @DisplayName("review — diff 为空时跳过审查")
    void emptyDiff() {
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER, INSTALLATION_ID))
                .thenReturn("");

        orchestrator.review(buildPayload());

        verify(gitHubClient).getPullRequestDiff(anyString(), anyString(), anyInt(), anyLong());
        verify(modelA, never()).chat(anyList());
        verify(gitHubClient, never()).postReview(anyString(), anyString(), anyInt(), any(), anyLong());
    }

    @Test
    @DisplayName("review — LLM 调用异常时贴 error comment")
    void llmErrorPostsErrorComment() {
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER, INSTALLATION_ID))
                .thenReturn(DIFF);
        doThrow(new RuntimeException("LLM 超时"))
                .when(modelA).chat(anyList());

        orchestrator.review(buildPayload());

        verify(gitHubClient).postReview(eq(OWNER), eq(REPO), eq(PR_NUMBER),
                contains("PRoverlap 审查异常"), eq(INSTALLATION_ID));
    }

    @Test
    @DisplayName("review — diff 拉取失败时贴 error comment")
    void diffFetchErrorPostsErrorComment() {
        when(gitHubClient.getPullRequestDiff(OWNER, REPO, PR_NUMBER, INSTALLATION_ID))
                .thenThrow(new RuntimeException("API 限流"));

        orchestrator.review(buildPayload());

        verify(gitHubClient).postReview(eq(OWNER), eq(REPO), eq(PR_NUMBER),
                contains("PRoverlap 审查异常"), eq(INSTALLATION_ID));
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
