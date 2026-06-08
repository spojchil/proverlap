package io.github.spojchil.proverlap.tier;

import io.github.spojchil.proverlap.config.TierProperties;
import io.github.spojchil.proverlap.model.enums.TierLevel;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Tier 分级器 — 根据 diff 大小和文件敏感度判定审查深度。
 *
 * <p>判定优先级：
 *
 * <ol>
 *   <li>diff 行数超 t2MaxDiffLines(500) → Tier 3
 *   <li>变更文件包含敏感路径（auth/security/sql/migration）→ Tier 3
 *   <li>diff ≤ 50 行且全为非代码文件 → Tier 1
 *   <li>其余情况 → Tier 2
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TierClassifier {

    private final TierProperties tierProperties;

    /** 敏感路径模式 — 匹配 auth/security/sql/migration 目录下的文件 */
    private static final List<Pattern> SENSITIVE_PATTERNS =
            List.of(
                    Pattern.compile("(^|/)auth/", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("(^|/)security/", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("(^|/)sql/", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("(^|/)migration/", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("(^|/)flyway/", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("(^|/)liquibase/", Pattern.CASE_INSENSITIVE));

    /** 敏感文件名模式 — 含安全相关关键词的文件 */
    private static final List<Pattern> SENSITIVE_NAME_PATTERNS =
            List.of(
                    Pattern.compile("Security", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("Auth", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("Login", Pattern.CASE_INSENSITIVE),
                    Pattern.compile("Password", Pattern.CASE_INSENSITIVE));

    /** 非代码文件扩展名 */
    private static final Set<String> NON_CODE_EXTENSIONS =
            Set.of(
                    ".md",
                    ".yml",
                    ".yaml",
                    ".properties",
                    ".xml",
                    ".json",
                    ".txt",
                    ".gitignore",
                    ".editorconfig",
                    ".csv");

    /**
     * 判定 Tier 审查深度。
     *
     * @param totalLines diff 总行数（新增 + 删除）
     * @param changedFiles 变更文件路径列表，如 {@code ["src/auth/LoginService.java", "README.md"]}
     * @return 对应 Tier 等级
     */
    public TierLevel classify(int totalLines, List<String> changedFiles) {
        if (totalLines <= 0 || changedFiles.isEmpty()) {
            log.debug("diff 为空或无文件变更，默认 Tier 1");
            return TierLevel.TIER_1;
        }

        // Tier 3: 超大 diff
        if (totalLines > tierProperties.getT2MaxDiffLines()) {
            log.info("Tier → T3（行数 {} > {}）", totalLines, tierProperties.getT2MaxDiffLines());
            return TierLevel.TIER_3;
        }

        // Tier 3: 敏感文件
        if (hasSensitiveFile(changedFiles)) {
            log.info("Tier → T3（包含敏感文件）");
            return TierLevel.TIER_3;
        }

        // Tier 1: 小变更且全为非代码文件
        if (totalLines <= tierProperties.getT1MaxDiffLines() && allNonCode(changedFiles)) {
            log.info("Tier → T1（{} 行，全为非代码文件）", totalLines);
            return TierLevel.TIER_1;
        }

        // 其余 → Tier 2
        log.info("Tier → T2（{} 行，{} 个文件）", totalLines, changedFiles.size());
        return TierLevel.TIER_2;
    }

    /** 任一文件路径匹配敏感模式 */
    private boolean hasSensitiveFile(List<String> files) {
        return files.stream()
                .anyMatch(
                        f -> {
                            for (Pattern p : SENSITIVE_PATTERNS) {
                                if (p.matcher(f).find()) return true;
                            }
                            String name = f.substring(f.lastIndexOf('/') + 1);
                            for (Pattern p : SENSITIVE_NAME_PATTERNS) {
                                if (p.matcher(name).find()) return true;
                            }
                            return false;
                        });
    }

    /** 所有文件都是非代码文件 */
    private boolean allNonCode(List<String> files) {
        return files.stream()
                .allMatch(
                        f -> {
                            int dot = f.lastIndexOf('.');
                            if (dot < 0) return false; // 无扩展名 → 可能是代码文件
                            String ext = f.substring(dot).toLowerCase();
                            return NON_CODE_EXTENSIONS.contains(ext);
                        });
    }
}
