package io.github.spojchil.proverlap.aggregation;

import io.github.spojchil.proverlap.model.dto.Finding;
import io.github.spojchil.proverlap.review.DimensionReviewer.DimensionResult;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 审查结果聚合器。
 *
 * <p>多维度审查结果汇总：去重（同文件同行号同严重度）→ 排序（阻断>警告>建议） → 标注来源（双模型共识/分歧/单模型）。
 */
@Slf4j
@Component
public class ResultAggregator {

    private static final Map<String, Integer> SEVERITY_ORDER = Map.of("阻断", 0, "警告", 1, "建议", 2);

    /** 聚合结果：Markdown 文本 + 去重后的阻断计数 */
    public record AggregationResult(String text, int blockingCount) {}

    /**
     * 聚合多维度审查结果。
     *
     * @param results 各维度审查结果列表
     * @return 聚合结果（文本 + 去重阻断计数）
     */
    public AggregationResult aggregate(List<DimensionResult> results) {
        if (results == null || results.isEmpty()) return new AggregationResult("审查未发现需要关注的维度。", 0);

        // 1. 收集所有 Findings + 去重
        List<Finding> allFindings = new ArrayList<>();
        for (DimensionResult r : results) {
            if (r.findings() != null) {
                allFindings.addAll(r.findings());
            }
        }
        List<Finding> deduped = new ArrayList<>(deduplicate(allFindings));

        // 2. 排序
        deduped.sort(
                Comparator.comparingInt(
                                (Finding f) -> SEVERITY_ORDER.getOrDefault(f.getSeverity(), 3))
                        .thenComparing(Finding::getFile)
                        .thenComparingInt(Finding::getLine));

        // 3. 统计
        long blocking = deduped.stream().filter(f -> "阻断".equals(f.getSeverity())).count();
        long warning = deduped.stream().filter(f -> "警告".equals(f.getSeverity())).count();
        long suggestion = deduped.stream().filter(f -> "建议".equals(f.getSeverity())).count();
        long cvCount = results.stream().filter(DimensionResult::crossValidated).count();
        long singleCount = results.size() - cvCount;

        // 4. 格式化输出
        StringBuilder sb = new StringBuilder();
        sb.append("## 审查总结\n");
        sb.append(blocking)
                .append(" 阻断 · ")
                .append(warning)
                .append(" 警告 · ")
                .append(suggestion)
                .append(" 建议");
        sb.append(" | ").append(cvCount).append(" 双模型交叉验证 · ").append(singleCount).append(" 单模型\n");
        sb.append("\n---\n\n");

        // 按维度输出（保持原始顺序）
        for (DimensionResult r : results) {
            sb.append(r.findingsText());
            sb.append("\n\n---\n\n");
        }

        return new AggregationResult(sb.toString(), (int) blocking);
    }

    /**
     * 去重：同文件 + 行号邻近(±5行) + 同严重度 → 保留置信度最高的。
     *
     * <p>严重度不同不算重复（阻断和警告可能是同一行的不同问题）。
     */
    List<Finding> deduplicate(List<Finding> findings) {
        if (findings.size() <= 1) return new ArrayList<>(findings);

        List<Finding> result = new ArrayList<>(findings);
        int removed = 0;

        for (int i = 0; i < result.size(); i++) {
            Finding fi = result.get(i);
            if (fi == null) continue;

            for (int j = i + 1; j < result.size(); j++) {
                Finding fj = result.get(j);
                if (fj == null) continue;

                if (isSameFinding(fi, fj)) {
                    // 保留置信度高的，合并描述
                    if (fi.getConfidence() >= fj.getConfidence()) {
                        result.set(j, null);
                    } else {
                        result.set(i, null);
                        break;
                    }
                    removed++;
                }
            }
        }

        List<Finding> deduped = result.stream().filter(Objects::nonNull).toList();
        if (removed > 0) {
            log.info("去重: {} → {} 条发现", findings.size(), deduped.size());
        }
        return deduped;
    }

    /** 两个 Finding 是否同一问题 */
    static boolean isSameFinding(Finding a, Finding b) {
        if (!a.getFile().equals(b.getFile())) return false;
        if (Math.abs(a.getLine() - b.getLine()) > 5) return false;
        return a.getSeverity().equals(b.getSeverity());
    }
}
