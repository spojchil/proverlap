package io.github.spojchil.proverlap.review;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.github.spojchil.proverlap.config.ModelProperties;
import io.github.spojchil.proverlap.model.dto.CrossValidationResult;
import io.github.spojchil.proverlap.model.dto.Finding;
import io.github.spojchil.proverlap.model.enums.TierLevel;
import io.github.spojchil.proverlap.review.prompts.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 维度 × 模型调度器。
 * <p>
 * 解析 PR 标题获取类型（feat/fix/perf/...），查矩阵激活对应维度，
 * 按维度独立审查，双模型 CV 用于安全+正确性，单模型用于次要维度。
 */
@Slf4j
@Service
public class DimensionReviewer {

    @Qualifier("modelA")
    private final ChatModel modelA;
    @Qualifier("modelB")
    private final ChatModel modelB;
    @Qualifier("reviewExecutor")
    private final Executor executor;
    private final ModelProperties modelProperties;
    private final FindingParser findingParser;
    private final CrossValidator crossValidator;
    private final CrossValidationCommentFormatter commentFormatter;
    private final SecurityPrompt securityPrompt;
    private final CorrectnessPrompt correctnessPrompt;
    private final DesignPrompt designPrompt;
    private final PerformancePrompt performancePrompt;
    private final MaintainabilityPrompt maintainabilityPrompt;
    private final TestCoveragePrompt testCoveragePrompt;

    /** 标准 Conventional Commits: type(scope)!: / type: / type! / type( */
    private static final Pattern CONVENTIONAL_TYPE = Pattern.compile(
            "^(feat|fix|perf|refactor|docs|style|chore|test|build|ci|revert)" +
            "[\\(!:\\s\\[#]", Pattern.CASE_INSENSITIVE);

    /** Issue 引用: Fix #NNN: / Resolves #NNN: */
    private static final Pattern ISSUE_FIX = Pattern.compile(
            "^(fix(?:es)?|resolve(?:s)?)[:\\s]+#\\d+", Pattern.CASE_INSENSITIVE);

    /** 祈使动词 → PR 类型映射 */
    private static final java.util.Map<String, String> IMPERATIVE_TYPE = java.util.Map.ofEntries(
            java.util.Map.entry("add", "feat"), java.util.Map.entry("adding", "feat"),
            java.util.Map.entry("fix", "fix"), java.util.Map.entry("fixes", "fix"),
            java.util.Map.entry("remove", "chore"), java.util.Map.entry("removes", "chore"),
            java.util.Map.entry("delete", "chore"), java.util.Map.entry("deletes", "chore"),
            java.util.Map.entry("update", "feat"), java.util.Map.entry("updates", "feat"),
            java.util.Map.entry("bump", "chore"), java.util.Map.entry("bumps", "chore"),
            java.util.Map.entry("refactor", "refactor"), java.util.Map.entry("revert", "revert"),
            java.util.Map.entry("document", "docs"), java.util.Map.entry("docs", "docs"),
            java.util.Map.entry("adds", "feat"), java.util.Map.entry("added", "feat"),
            java.util.Map.entry("fixed", "fix"), java.util.Map.entry("removed", "chore"),
            java.util.Map.entry("updated", "feat"), java.util.Map.entry("deleted", "chore"));

    /** 维度 × 模型矩阵：PR 类型 → 维度任务列表 */
    private final Map<String, List<DimensionTask>> matrix;

    public DimensionReviewer(@Qualifier("modelA") ChatModel modelA,
                             @Qualifier("modelB") ChatModel modelB,
                             @Qualifier("reviewExecutor") Executor executor,
                             ModelProperties modelProperties,
                             FindingParser findingParser,
                             CrossValidator crossValidator,
                             CrossValidationCommentFormatter commentFormatter,
                             SecurityPrompt securityPrompt,
                             CorrectnessPrompt correctnessPrompt,
                             DesignPrompt designPrompt,
                             PerformancePrompt performancePrompt,
                             MaintainabilityPrompt maintainabilityPrompt,
                             TestCoveragePrompt testCoveragePrompt) {
        this.modelA = modelA;
        this.modelB = modelB;
        this.executor = executor;
        this.modelProperties = modelProperties;
        this.findingParser = findingParser;
        this.crossValidator = crossValidator;
        this.commentFormatter = commentFormatter;
        this.securityPrompt = securityPrompt;
        this.correctnessPrompt = correctnessPrompt;
        this.designPrompt = designPrompt;
        this.performancePrompt = performancePrompt;
        this.maintainabilityPrompt = maintainabilityPrompt;
        this.testCoveragePrompt = testCoveragePrompt;
        this.matrix = buildMatrix();
    }

    /**
     * 按 PR 类型和 Tier 执行多维度审查。
     *
     * @param prTitle  PR 标题（如 "feat: 新增 OAuth2 登录"）
     * @param context  审查上下文（规范文件 + 完整文件 + diff）
     * @param tier     Tier 分级
     * @return 各维度审查结果列表
     */
    public List<DimensionResult> review(String prTitle, String context, TierLevel tier) {
        String prType = parseType(prTitle);
        List<DimensionTask> tasks = selectTasks(prType, tier);

        log.info("PR 类型: {} ({}), Tier: {}, 激活维度: {}", prTitle, prType, tier.getCode(),
                tasks.stream().map(DimensionTask::dimension).toList());

        List<CompletableFuture<DimensionResult>> futures = new ArrayList<>();

        for (DimensionTask task : tasks) {
            if (task.modelCount() == 2) {
                futures.add(dualModelReview(task, context));
            } else {
                futures.add(singleModelReview(task, context));
            }
        }

        return futures.stream().map(f -> {
            try {
                return f.get(modelProperties.getTimeoutSeconds(), TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("维度 {} 审查超时或失败: {}", taskDimension(f), e.getMessage());
                return DimensionResult.of(taskDimension(f), "审查超时", false);
            }
        }).toList();
    }

    // ==================== 内部方法 ====================

    /** 单模型审查 */
    private CompletableFuture<DimensionResult> singleModelReview(DimensionTask task, String context) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                log.info("单模型审查: {}", task.dimension());
                String raw = callModel(modelA, task.prompt(), context);
                List<Finding> findings = findingParser.parse(raw, "modelA");
                String formatted = formatSingleModelFindings(task.dimension(), findings, raw);
                return DimensionResult.of(task.dimension(), formatted, false, findings);
            } catch (Exception e) {
                log.error("单模型审查失败({}): {}", task.dimension(), e.getMessage());
                return DimensionResult.of(task.dimension(), "审查失败: " + e.getMessage(), false);
            }
        }, executor);
    }

    /** 双模型交叉验证审查 */
    private CompletableFuture<DimensionResult> dualModelReview(DimensionTask task, String context) {
        CompletableFuture<List<Finding>> fa = CompletableFuture.supplyAsync(
                () -> callAndParse(modelA, task.prompt(), context, "modelA"), executor);
        CompletableFuture<List<Finding>> fb = CompletableFuture.supplyAsync(
                () -> callAndParse(modelB, task.prompt(), context, "modelB"), executor);

        return fa.thenCombine(fb, (findingsA, findingsB) -> {
            CrossValidationResult cross = crossValidator.compare(findingsA, findingsB);
            String formatted = commentFormatter.format(cross);
            List<Finding> allFindings = new ArrayList<>();
            allFindings.addAll(cross.getConsensus());
            cross.getModelAOnly().forEach(f -> allFindings.add(f));
            cross.getModelBOnly().forEach(f -> allFindings.add(f));
            return DimensionResult.of(task.dimension(), formatted, true, allFindings);
        }).exceptionally(e -> {
            log.error("双模型审查失败({}): {}", task.dimension(), e.getMessage());
            return DimensionResult.of(task.dimension(), "审查失败: " + e.getMessage(), false);
        });
    }

    /** 调用模型 + 解析 JSON */
    private List<Finding> callAndParse(ChatModel model, ReviewPrompt prompt, String context, String modelName) {
        try {
            String raw = callModel(model, prompt, context);
            return findingParser.parse(raw, modelName);
        } catch (Exception e) {
            log.error("模型调用失败({}): {}", modelName, e.getMessage());
            return List.of();
        }
    }

    /** 调用单个 LLM */
    private String callModel(ChatModel model, ReviewPrompt prompt, String context) {
        ChatResponse response = model.chat(List.of(
                SystemMessage.from(prompt.system()),
                UserMessage.from(context)));
        return response.aiMessage().text();
    }

    /** 解析 PR 标题 → PR 类型（feat/fix/perf/...），未识别回退 "feat" */
    static String parseType(String prTitle) {
        if (prTitle == null || prTitle.isBlank()) return "feat";
        String title = prTitle.trim();

        // 1. 标准 Conventional Commits（含大小写变体，feat:/Fix:/FIX: 等）
        Matcher conv = CONVENTIONAL_TYPE.matcher(title);
        if (conv.find()) return conv.group(1).toLowerCase();

        // 2. Issue 引用（Fix #123: / Resolves #456:）
        Matcher issue = ISSUE_FIX.matcher(title);
        if (issue.find()) return "fix";

        // 3. 祈使动词（Add / Remove / Update / Bump ...）
        String firstWord = title.split("\\s+", 2)[0].toLowerCase().replaceAll("[^a-z]", "");
        String type = IMPERATIVE_TYPE.get(firstWord);
        if (type != null) return type;

        return "feat";
    }

    /** 按 PR 类型 + Tier 选择维度任务 */
    private List<DimensionTask> selectTasks(String prType, TierLevel tier) {
        List<DimensionTask> tasks = matrix.getOrDefault(prType, matrix.get("feat"));

        if (tier == TierLevel.TIER_1) {
            // T1: 只保留第一个单模型维度
            return tasks.stream()
                    .filter(t -> t.modelCount() == 1)
                    .limit(1)
                    .toList();
        }

        if (tier == TierLevel.TIER_2) {
            // T2: 跳过设计维度（最高层，T3 才有）
            return tasks.stream()
                    .filter(t -> !"design".equals(t.dimension()))
                    .toList();
        }

        return tasks; // T3: 全维度
    }

    /** 格式化单模型审查结果为 Markdown */
    private static String formatSingleModelFindings(String dimension, List<Finding> findings, String raw) {
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(dimension).append("（单模型 · 需复核）\n\n");

        if (findings.isEmpty()) {
            // 尝试从 raw JSON 提取 summary
            sb.append("未发现 ").append(dimension).append(" 相关问题。\n");
            return sb.toString();
        }

        sb.append(findings.size()).append(" 个发现：\n\n");
        for (Finding f : findings) {
            sb.append("> **").append(f.getSeverity()).append("** `")
                    .append(f.getFile()).append("` L").append(f.getLine())
                    .append(" — ").append(f.getTitle()).append("\n");
            sb.append("> ").append(f.getDescription()).append("\n");
            if (f.getSuggestion() != null && !f.getSuggestion().isBlank()) {
                sb.append("> 建议: ").append(f.getSuggestion()).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** 从 CompletableFuture 推测维度名（用于异常日志） */
    private static String taskDimension(CompletableFuture<?> future) {
        return "unknown";
    }

    // ==================== 矩阵定义 ====================

    private Map<String, List<DimensionTask>> buildMatrix() {
        return Map.of(
                "feat", List.of(
                        DimensionTask.of("design", designPrompt, 1),
                        DimensionTask.of("correctness", correctnessPrompt, 2),
                        DimensionTask.of("security", securityPrompt, 2),
                        DimensionTask.of("maintainability", maintainabilityPrompt, 1),
                        DimensionTask.of("test", testCoveragePrompt, 1)),
                "fix", List.of(
                        DimensionTask.of("correctness", correctnessPrompt, 2),
                        DimensionTask.of("security", securityPrompt, 2)),
                "perf", List.of(
                        DimensionTask.of("correctness", correctnessPrompt, 2),
                        DimensionTask.of("performance", performancePrompt, 1)),
                "refactor", List.of(
                        DimensionTask.of("correctness", correctnessPrompt, 2),
                        DimensionTask.of("maintainability", maintainabilityPrompt, 1)),
                "docs", List.of(),
                "style", List.of(),
                "chore", List.of(),
                "test", List.of(
                        DimensionTask.of("test", testCoveragePrompt, 1)),
                "build", List.of(),
                "ci", List.of());
    }

    /** 维度任务：维度名 + Prompt + 模型数量 */
    public record DimensionTask(String dimension, ReviewPrompt prompt, int modelCount) {
        static DimensionTask of(String dimension, ReviewPrompt prompt, int modelCount) {
            return new DimensionTask(dimension, prompt, modelCount);
        }
    }

    /** 维度审查结果 */
    public record DimensionResult(String dimension, String findingsText, boolean crossValidated,
                                  List<Finding> findings) {
        public static DimensionResult of(String dimension, String findingsText, boolean crossValidated) {
            return new DimensionResult(dimension, findingsText, crossValidated, List.of());
        }
        public static DimensionResult of(String dimension, String findingsText, boolean crossValidated,
                                         List<Finding> findings) {
            return new DimensionResult(dimension, findingsText, crossValidated, findings);
        }
    }
}
