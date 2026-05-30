package io.github.spojchil.proverlap.review;

import io.github.spojchil.proverlap.model.dto.CrossValidationResult;
import io.github.spojchil.proverlap.model.dto.Finding;
import java.util.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 双模型交叉比对器。
 * <p>
 * 将模型 A 和模型 B 的发现按文件路径 + 行号邻近度匹配，
 * 输出共识（双模型一致）、分歧（同位置观点不同）、单模型发现三组结果。
 */
@Slf4j
@Component
public class CrossValidator {

    /** 行号匹配容忍度 */
    private static final int LINE_TOLERANCE = 5;

    /** 严重度匹配映射 */
    private static final Map<String, Integer> SEVERITY_WEIGHT = Map.of(
            "阻断", 3, "警告", 2, "建议", 1);

    /**
     * 比对模型 A 和 B 的发现。
     *
     * @param findingsA 模型 A 的发现列表
     * @param findingsB 模型 B 的发现列表
     * @return 交叉比对结果
     */
    public CrossValidationResult compare(List<Finding> findingsA, List<Finding> findingsB) {
        List<Finding> consensus = new ArrayList<>();
        List<CrossValidationResult.FindingPair> divergences = new ArrayList<>();
        List<Finding> modelAOnly = new ArrayList<>();
        List<Finding> modelBOnly = new ArrayList<>();

        boolean[] matchedB = new boolean[findingsB.size()];

        for (Finding fa : findingsA) {
            int bestIdx = -1;
            int bestScore = 0;

            for (int j = 0; j < findingsB.size(); j++) {
                if (matchedB[j]) continue;
                Finding fb = findingsB.get(j);
                int score = matchScore(fa, fb);
                if (score > bestScore) {
                    bestScore = score;
                    bestIdx = j;
                }
            }

            if (bestIdx >= 0 && bestScore >= 2) {
                Finding fb = findingsB.get(bestIdx);
                matchedB[bestIdx] = true;

                if (bestScore >= 3) {
                    // 文件+行号+严重度都匹配 → 共识（创建新 Finding，不修改输入）
                    Finding merged = Finding.builder()
                            .severity(fa.getSeverity()).file(fa.getFile()).line(fa.getLine())
                            .title(fa.getTitle()).description(fa.getDescription())
                            .suggestion(fa.getSuggestion()).modelSource(fa.getModelSource())
                            .confidence((fa.getConfidence() + fb.getConfidence()) / 2.0)
                            .build();
                    consensus.add(merged);
                } else {
                    // 文件+行号匹配但严重度或标题差异大 → 分歧
                    divergences.add(CrossValidationResult.FindingPair.builder()
                            .modelA(fa).modelB(fb)
                            .file(fa.getFile()).line(fa.getLine())
                            .build());
                }
            } else {
                modelAOnly.add(fa);
            }
        }

        // 收集 B 独有
        for (int j = 0; j < findingsB.size(); j++) {
            if (!matchedB[j]) {
                modelBOnly.add(findingsB.get(j));
            }
        }

        log.info("交叉比对完成: 共识={}, 分歧={}, A独有={}, B独有={}",
                consensus.size(), divergences.size(), modelAOnly.size(), modelBOnly.size());

        return CrossValidationResult.builder()
                .consensus(consensus)
                .divergences(divergences)
                .modelAOnly(modelAOnly)
                .modelBOnly(modelBOnly)
                .build();
    }

    /**
     * 计算两个发现的匹配分数。
     * <p>
     * 文件不同 → 0（不匹配）<br>
     * 行号不邻近 → 0（同一文件不同位置，视为不同发现）<br>
     * 行号邻近 → 2 + 严重度相同(+1) + 关键词匹配(+1)，满分 4<br>
     * ≥ 3 共识，= 2 分歧
     */
    private int matchScore(Finding a, Finding b) {
        int score = 0;

        // 文件路径相同
        if (!a.getFile().equals(b.getFile())) return 0;

        // 行号邻近（不邻近 → 不是同一发现）
        if (Math.abs(a.getLine() - b.getLine()) <= LINE_TOLERANCE) {
            score += 2;
        } else {
            return 0;
        }

        // 严重度相同
        if (a.getSeverity().equals(b.getSeverity())) {
            score += 1;
        }

        // 标题关键词匹配
        if (hasCommonKeyword(a.getTitle(), b.getTitle())) {
            score += 1;
        }

        return score;
    }

    /** 两个标题是否有长度 > 3 的共同词 */
    private boolean hasCommonKeyword(String titleA, String titleB) {
        String[] wordsA = titleA.toLowerCase().split("\\s+");
        String[] wordsB = titleB.toLowerCase().split("\\s+");
        for (String wa : wordsA) {
            if (wa.length() <= 3) continue;
            for (String wb : wordsB) {
                if (wa.equals(wb)) return true;
            }
        }
        return false;
    }
}
