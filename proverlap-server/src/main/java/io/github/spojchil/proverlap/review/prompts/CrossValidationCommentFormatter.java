package io.github.spojchil.proverlap.review.prompts;

import io.github.spojchil.proverlap.model.dto.CrossValidationResult;
import io.github.spojchil.proverlap.model.dto.Finding;
import org.springframework.stereotype.Component;

/** 将交叉比对结果格式化为 GitHub Review Comment 的 Markdown 文本。 */
@Component
public class CrossValidationCommentFormatter {

    public String format(CrossValidationResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("## 审查结果 [双模型交叉验证]\n\n");

        // 共识发现 — 高置信
        if (!result.getConsensus().isEmpty()) {
            sb.append("### 高置信 — 双模型一致 (").append(result.getConsensus().size()).append(")\n\n");
            for (Finding f : result.getConsensus()) {
                sb.append(formatFinding(f, ""));
            }
        }

        // 分歧 — 需人类判断
        if (!result.getDivergences().isEmpty()) {
            sb.append("### 分歧 — 需人类判断 (").append(result.getDivergences().size()).append(")\n\n");
            for (CrossValidationResult.FindingPair pair : result.getDivergences()) {
                sb.append("> `")
                        .append(pair.getFile())
                        .append("` L")
                        .append(pair.getLine())
                        .append("\n");
                sb.append("> DeepSeek: ")
                        .append(truncate(pair.getModelA().getTitle(), 60))
                        .append("\n");
                sb.append("> mimo: ")
                        .append(truncate(pair.getModelB().getTitle(), 60))
                        .append("\n\n");
            }
        }

        // 仅 A 发现 — 中置信
        if (!result.getModelAOnly().isEmpty()) {
            sb.append("### 仅 DeepSeek 发现 (").append(result.getModelAOnly().size()).append(")\n\n");
            for (Finding f : result.getModelAOnly()) {
                sb.append(formatFinding(f, " [需人类复核]"));
            }
        }

        // 仅 B 发现 — 中置信
        if (!result.getModelBOnly().isEmpty()) {
            sb.append("### 仅 mimo 发现 (").append(result.getModelBOnly().size()).append(")\n\n");
            for (Finding f : result.getModelBOnly()) {
                sb.append(formatFinding(f, " [需人类复核]"));
            }
        }

        // 无发现
        if (result.getConsensus().isEmpty()
                && result.getDivergences().isEmpty()
                && result.getModelAOnly().isEmpty()
                && result.getModelBOnly().isEmpty()) {
            sb.append("未发现安全问题。\n\n");
        }

        return sb.toString();
    }

    private String formatFinding(Finding f, String suffix) {
        StringBuilder sb = new StringBuilder();
        sb.append("> **")
                .append(f.getSeverity())
                .append("** `")
                .append(f.getFile())
                .append("` L")
                .append(f.getLine())
                .append(" — ")
                .append(f.getTitle())
                .append(suffix)
                .append("\n");
        if (f.getDescription() != null && !f.getDescription().isBlank()) {
            sb.append("> ").append(f.getDescription()).append("\n");
        }
        if (f.getSuggestion() != null && !f.getSuggestion().isBlank()) {
            sb.append("> 建议: ").append(f.getSuggestion()).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
