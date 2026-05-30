package io.github.spojchil.proverlap.review;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.spojchil.proverlap.model.enums.TierLevel;
import io.github.spojchil.proverlap.review.prompts.*;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * DimensionReviewer 维度调度单元测试。
 */
@DisplayName("DimensionReviewer 维度调度单元测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DimensionReviewerTest {

    @Mock
    private ChatModel modelA;
    @Mock
    private ChatModel modelB;

    private final Executor executor = Runnable::run;
    private final FindingParser findingParser = new FindingParser();
    private final CrossValidator crossValidator = new CrossValidator();
    private final CrossValidationCommentFormatter commentFormatter = new CrossValidationCommentFormatter();
    private final SecurityPrompt securityPrompt = new SecurityPrompt();
    private final CorrectnessPrompt correctnessPrompt = new CorrectnessPrompt();
    private final DesignPrompt designPrompt = new DesignPrompt();
    private final PerformancePrompt performancePrompt = new PerformancePrompt();
    private final MaintainabilityPrompt maintainabilityPrompt = new MaintainabilityPrompt();
    private final TestCoveragePrompt testCoveragePrompt = new TestCoveragePrompt();

    private DimensionReviewer reviewer;
    private static final String CONTEXT = "## 变更文件\n```java\ntest\n```";
    private static final String EMPTY_JSON = "{\"findings\":[],\"summary\":\"无问题\"}";

    @BeforeEach
    void setUp() {
        reviewer = new DimensionReviewer(modelA, modelB, executor,
                findingParser, crossValidator, commentFormatter,
                securityPrompt, correctnessPrompt, designPrompt,
                performancePrompt, maintainabilityPrompt, testCoveragePrompt);
    }

    // ==================== PR 类型解析 ====================

    @Test
    @DisplayName("parseType — 标准 Conventional Commits")
    void parseStandard() {
        assertEquals("feat", DimensionReviewer.parseType("feat: 新增登录"));
        assertEquals("feat", DimensionReviewer.parseType("feat(user): add login"));
        assertEquals("fix", DimensionReviewer.parseType("fix: 修复竞态"));
        assertEquals("fix", DimensionReviewer.parseType("fix(auth): 修复超时"));
        assertEquals("docs", DimensionReviewer.parseType("docs: 更新文档"));
        assertEquals("chore", DimensionReviewer.parseType("chore(deps): bump version"));
        assertEquals("refactor", DimensionReviewer.parseType("refactor(api): clean up"));
        assertEquals("ci", DimensionReviewer.parseType("ci: pin actions"));
        assertEquals("revert", DimensionReviewer.parseType("revert: 回退提交"));
    }

    @Test
    @DisplayName("parseType — 大小写变体")
    void parseCaseVariants() {
        assertEquals("fix", DimensionReviewer.parseType("Fix: 修复问题"));
        assertEquals("fix", DimensionReviewer.parseType("FIX: 紧急修复"));
        assertEquals("docs", DimensionReviewer.parseType("Docs: 文档更新"));
        assertEquals("feat", DimensionReviewer.parseType("Feat: 新功能"));
    }

    @Test
    @DisplayName("parseType — Issue 引用格式")
    void parseIssueReference() {
        assertEquals("fix", DimensionReviewer.parseType("Fix #5878: 修复 DeepSeek 推理"));
        assertEquals("fix", DimensionReviewer.parseType("Fixes #123: handle edge case"));
        assertEquals("fix", DimensionReviewer.parseType("Resolves #456: 解决超时问题"));
    }

    @Test
    @DisplayName("parseType — 祈使动词无前缀")
    void parseImperative() {
        assertEquals("feat", DimensionReviewer.parseType("Add OAuth2 support"));
        assertEquals("feat", DimensionReviewer.parseType("Adding new agent class"));
        assertEquals("fix", DimensionReviewer.parseType("Fix structured output leaks"));
        assertEquals("chore", DimensionReviewer.parseType("Remove deprecated method"));
        assertEquals("chore", DimensionReviewer.parseType("Bump version to 1.14.6"));
        assertEquals("feat", DimensionReviewer.parseType("Update CLI docs links"));
        assertEquals("docs", DimensionReviewer.parseType("Document checkpointing behavior"));
        assertEquals("refactor", DimensionReviewer.parseType("Refactor memory leak fix"));
        assertEquals("revert", DimensionReviewer.parseType("Revert \"feat: old approach\""));
    }

    @Test
    @DisplayName("parseType — 无法识别回退 feat")
    void parseFallback() {
        assertEquals("feat", DimensionReviewer.parseType("更新了登录逻辑"));
        assertEquals("feat", DimensionReviewer.parseType("AI Agent Privacy Notice"));
        assertEquals("feat", DimensionReviewer.parseType(""));
        assertEquals("feat", DimensionReviewer.parseType(null));
    }

    // ==================== 维度激活 ====================

    @Test
    @DisplayName("review — feat PR 激活 5 个维度")
    void featActivatesAllDimensions() {
        stubModels();

        List<DimensionReviewer.DimensionResult> results = reviewer.review(
                "feat: 新增功能", CONTEXT, TierLevel.TIER_3);

        assertEquals(5, results.size(),
                "feat T3 应激活 5 个维度: design, correctness, security, maintainability, test");
    }

    @Test
    @DisplayName("review — fix PR 激活 2 个维度")
    void fixActivatesTwoDimensions() {
        stubModels();

        List<DimensionReviewer.DimensionResult> results = reviewer.review(
                "fix: 修复 bug", CONTEXT, TierLevel.TIER_3);

        assertEquals(2, results.size(),
                "fix 应只激活 correctness + security");
    }

    @Test
    @DisplayName("review — perf PR 激活 2 个维度")
    void perfActivatesCorrectnessAndPerformance() {
        stubModels();

        List<DimensionReviewer.DimensionResult> results = reviewer.review(
                "perf: 优化查询", CONTEXT, TierLevel.TIER_3);

        assertEquals(2, results.size());
        assertTrue(results.stream().anyMatch(r -> r.dimension().equals("correctness")));
        assertTrue(results.stream().anyMatch(r -> r.dimension().equals("performance")));
    }

    @Test
    @DisplayName("review — docs/chore/style PR 激活 0 个维度")
    void docsActivatesNothing() {
        List<DimensionReviewer.DimensionResult> results = reviewer.review(
                "docs: 更新 README", CONTEXT, TierLevel.TIER_3);

        assertTrue(results.isEmpty(), "docs PR 不需要审查");
    }

    // ==================== Tier 联动 ====================

    @Test
    @DisplayName("review — T1 只保留 1 个单模型维度")
    void tier1SingleModelOnly() {
        stubModels();

        List<DimensionReviewer.DimensionResult> results = reviewer.review(
                "feat: 新功能", CONTEXT, TierLevel.TIER_1);

        assertEquals(1, results.size());
        assertFalse(results.get(0).crossValidated(), "T1 不应有双模型 CV");
    }

    @Test
    @DisplayName("review — T2 跳过 design 维度")
    void tier2SkipsDesign() {
        stubModels();

        List<DimensionReviewer.DimensionResult> results = reviewer.review(
                "feat: 新功能", CONTEXT, TierLevel.TIER_2);

        assertTrue(results.stream().noneMatch(r -> "design".equals(r.dimension())),
                "T2 应跳过 design 维度");
        assertEquals(4, results.size());
    }

    // ==================== 工具方法 ====================

    private void stubModels() {
        doReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(EMPTY_JSON)).build())
                .when(modelA).chat(anyList());
        doReturn(ChatResponse.builder()
                        .aiMessage(AiMessage.from(EMPTY_JSON)).build())
                .when(modelB).chat(anyList());
    }
}
