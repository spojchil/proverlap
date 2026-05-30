package io.github.spojchil.proverlap.context;

import io.github.spojchil.proverlap.config.GitHubClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 审查上下文组装器。
 * <p>
 * 将 diff、变更文件的完整内容、项目规范文件拼装为 LLM 审查可用的完整上下文。
 * 控制总上下文大小，避免超出模型 token 限制。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContextBuilder {

    private final GitHubClient gitHubClient;

    /** 规范文件列表 */
    private static final List<String> SPEC_FILES = List.of(
            "CLAUDE.md", "CONTRIBUTING.md", ".editorconfig");

    /** 代码文件扩展名 */
    private static final Set<String> CODE_EXTENSIONS = Set.of(
            ".java", ".py", ".js", ".ts", ".jsx", ".tsx", ".go", ".rs",
            ".c", ".cpp", ".h", ".hpp", ".cs", ".rb", ".php", ".swift",
            ".kt", ".scala", ".sql");

    /** 上下文大小上限 */
    private static final int MAX_CONTEXT_SIZE = 60_000;
    /** 单文件最大行数 */
    private static final int MAX_FILE_LINES = 2000;
    /** 最多拉取文件数 */
    private static final int MAX_FILES = 5;
    /** 截断后的内容上限 */
    private static final int MAX_DIFF_SIZE = 40_000;

    /**
     * 组装审查上下文。
     *
     * @param owner    仓库 owner
     * @param repo     仓库名
     * @param diff     PR unified diff 文本
     * @return 组装后的完整上下文文本
     */
    public String build(String owner, String repo, String diff, String ref) {
        StringBuilder ctx = new StringBuilder();

        // 1. 项目规范文件
        appendSpecFiles(owner, repo, ctx, ref);

        // 2. 变更文件完整内容
        List<String> changedFiles = extractFiles(diff);
        appendFullFiles(owner, repo, changedFiles, ctx, ref);

        // 3. PR diff
        ctx.append("## 本次变更 (unified diff)\n\n```diff\n");
        String trimmed = diff.length() > MAX_DIFF_SIZE
                ? diff.substring(0, MAX_DIFF_SIZE) + "\n... (diff 已截断)"
                : diff;
        ctx.append(trimmed);
        ctx.append("\n```\n");

        int size = ctx.length();
        if (size > MAX_CONTEXT_SIZE) {
            log.warn("审查上下文过大 ({} 字节)，截断至 {} 字节", size, MAX_CONTEXT_SIZE);
        }
        return ctx.toString();
    }

    /** 追加项目规范文件 */
    private void appendSpecFiles(String owner, String repo, StringBuilder ctx, String ref) {
        boolean hasSpec = false;
        for (String spec : SPEC_FILES) {
            String content = gitHubClient.getRepoFile(owner, repo, spec, ref);
            if (content != null && !content.isBlank()) {
                if (!hasSpec) {
                    ctx.append("## 项目规范文件\n\n");
                    hasSpec = true;
                }
                ctx.append("### ").append(spec).append("\n\n")
                        .append(trimLines(content, 200))
                        .append("\n\n");
            }
        }
    }

    /** 追加变更文件的完整内容 */
    private void appendFullFiles(String owner, String repo, List<String> files, StringBuilder ctx, String ref) {
        List<String> codeFiles = files.stream()
                .filter(ContextBuilder::isCodeFile)
                .limit(MAX_FILES)
                .toList();

        if (codeFiles.isEmpty()) return;

        ctx.append("## 变更文件完整内容\n\n");
        for (String path : codeFiles) {
            String content = gitHubClient.getRepoFile(owner, repo, path, ref);
            if (content == null || content.isBlank()) continue;

            String ext = path.substring(path.lastIndexOf('.') + 1);
            ctx.append("### ").append(path).append("\n\n")
                    .append("```").append(ext).append("\n")
                    .append(trimLines(content, MAX_FILE_LINES))
                    .append("\n```\n\n");
        }
    }

    /** 从 diff 文本提取文件列表 */
    public static List<String> extractFiles(String diff) {
        List<String> files = new ArrayList<>();
        for (String line : diff.split("\n")) {
            if (line.startsWith("+++ b/")) {
                files.add(line.substring(6));
            }
        }
        return files;
    }

    /** 是否为代码文件 */
    static boolean isCodeFile(String path) {
        int dot = path.lastIndexOf('.');
        return dot >= 0 && CODE_EXTENSIONS.contains(path.substring(dot).toLowerCase());
    }

    /** 截取前 N 行 */
    static String trimLines(String content, int maxLines) {
        long totalLines = content.lines().count();
        if (totalLines <= maxLines) return content;
        String[] lines = content.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < maxLines && i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        sb.append("... (文件过长，已截断，完整内容 ")
                .append(totalLines).append(" 行)\n");
        return sb.toString();
    }
}
