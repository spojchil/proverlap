package io.github.spojchil.proverlap.aggregation;

import io.github.spojchil.proverlap.model.dto.Finding;
import io.github.spojchil.proverlap.review.DimensionReviewer.DimensionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ResultAggregator 聚合去重单元测试。
 */
@DisplayName("ResultAggregator 聚合去重单元测试")
class ResultAggregatorTest {

    private final ResultAggregator aggregator = new ResultAggregator();

    // ==================== 去重 ====================

    @Test
    @DisplayName("去重 — 同文件同行同严重度保留高置信度")
    void deduplicateSameIssue() {
        Finding f1 = buildFinding("阻断", "src/Auth.java", 42, "硬编码密钥", 0.9);
        Finding f2 = buildFinding("阻断", "src/Auth.java", 44, "硬编码密钥", 0.7);

        List<Finding> result = aggregator.deduplicate(List.of(f1, f2));

        assertEquals(1, result.size());
        assertEquals(0.9, result.get(0).getConfidence(), 0.01, "应保留置信度高的");
    }

    @Test
    @DisplayName("去重 — 严重度不同不合并")
    void deduplicateDifferentSeverity() {
        Finding f1 = buildFinding("阻断", "src/Auth.java", 42, "a", 0.9);
        Finding f2 = buildFinding("警告", "src/Auth.java", 42, "a", 0.7);

        List<Finding> result = aggregator.deduplicate(List.of(f1, f2));

        assertEquals(2, result.size(), "严重度不同不应合并");
    }

    @Test
    @DisplayName("去重 — 文件不同不合并")
    void deduplicateDifferentFile() {
        Finding f1 = buildFinding("阻断", "src/Auth.java", 42, "a", 0.9);
        Finding f2 = buildFinding("阻断", "src/Login.java", 42, "a", 0.7);

        List<Finding> result = aggregator.deduplicate(List.of(f1, f2));

        assertEquals(2, result.size());
    }

    @Test
    @DisplayName("去重 — 行号差距超过 5 不合并")
    void deduplicateFarLines() {
        Finding f1 = buildFinding("阻断", "src/Auth.java", 10, "a", 0.9);
        Finding f2 = buildFinding("阻断", "src/Auth.java", 20, "a", 0.7);

        List<Finding> result = aggregator.deduplicate(List.of(f1, f2));

        assertEquals(2, result.size(), "行号差 10 > 5 不应合并");
    }

    // ==================== 聚合 ====================

    @Test
    @DisplayName("聚合 — 汇总统计 + 排序")
    void aggregateWithStats() {
        Finding f1 = buildFinding("警告", "src/Bar.java", 10, "b", 0.5);
        Finding f2 = buildFinding("阻断", "src/Auth.java", 42, "a", 0.9);

        DimensionResult r1 = DimensionResult.of("security", "### security\nok", true, List.of(f1, f2));
        DimensionResult r2 = DimensionResult.of("correctness", "### correctness\nok", true, List.of());

        ResultAggregator.AggregationResult output = aggregator.aggregate(List.of(r1, r2));

        assertTrue(output.text().contains("审查总结"));
        assertTrue(output.text().contains("1 阻断"));
        assertTrue(output.text().contains("1 警告"));
        assertTrue(output.text().contains("双模型交叉验证"));
        assertTrue(output.text().contains("security"));
        assertTrue(output.text().contains("correctness"));
        assertEquals(1, output.blockingCount());
    }

    @Test
    @DisplayName("聚合 — 空结果")
    void aggregateEmpty() {
        ResultAggregator.AggregationResult output = aggregator.aggregate(List.of());
        assertTrue(output.text().contains("未发现"));
        assertEquals(0, output.blockingCount());
    }

    @Test
    @DisplayName("聚合 — null 输入")
    void aggregateNull() {
        ResultAggregator.AggregationResult output = aggregator.aggregate(null);
        assertTrue(output.text().contains("未发现"));
        assertEquals(0, output.blockingCount());
    }

    // ==================== 同问题判定 ====================

    @Test
    @DisplayName("同问题判定 — 完全相同 → true")
    void sameFindingIdentical() {
        Finding a = buildFinding("阻断", "src/Auth.java", 42, "x", 0.9);
        Finding b = buildFinding("阻断", "src/Auth.java", 43, "x", 0.7);
        assertTrue(ResultAggregator.isSameFinding(a, b));
    }

    @Test
    @DisplayName("同问题判定 — 文件不同 → false")
    void sameFindingDifferentFile() {
        Finding a = buildFinding("阻断", "src/A.java", 42, "x", 0.9);
        Finding b = buildFinding("阻断", "src/B.java", 42, "x", 0.7);
        assertFalse(ResultAggregator.isSameFinding(a, b));
    }

    @Test
    @DisplayName("同问题判定 — 严重度不同 → false")
    void sameFindingDifferentSeverity() {
        Finding a = buildFinding("阻断", "src/A.java", 42, "x", 0.9);
        Finding b = buildFinding("警告", "src/A.java", 42, "x", 0.7);
        assertFalse(ResultAggregator.isSameFinding(a, b));
    }

    private static Finding buildFinding(String severity, String file, int line,
                                         String title, double confidence) {
        return Finding.builder()
                .severity(severity).file(file).line(line)
                .title(title).description("desc").suggestion("sug")
                .modelSource("modelA").confidence(confidence)
                .build();
    }
}
