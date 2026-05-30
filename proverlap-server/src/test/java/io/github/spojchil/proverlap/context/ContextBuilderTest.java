package io.github.spojchil.proverlap.context;

import io.github.spojchil.proverlap.config.GitHubClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.any;

/**
 * ContextBuilder 上下文组装单元测试。
 */
@DisplayName("ContextBuilder 上下文组装单元测试")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ContextBuilderTest {

    @Mock
    private GitHubClient gitHubClient;

    private ContextBuilder builder;

    private static final String OWNER = "test";
    private static final String REPO = "repo";
    private static final String DIFF = "diff --git a/src/Foo.java b/src/Foo.java\n"
            + "--- a/src/Foo.java\n"
            + "+++ b/src/Foo.java\n"
            + "+return calc(a, b, c);\n";

    @BeforeEach
    void setUp() {
        builder = new ContextBuilder(gitHubClient);
        // 默认所有文件不存在，匹配 4 参数（owner, repo, path, ref）
        when(gitHubClient.getRepoFile(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);
    }

    // ==================== 规范文件 ====================

    @Test
    @DisplayName("build — 包含 CLAUDE.md 规范文件内容")
    void includesClaudeMd() {
        when(gitHubClient.getRepoFile(eq(OWNER), eq(REPO), eq("CLAUDE.md"), anyString()))
                .thenReturn("# 项目规范\n使用 Java 21");

        String result = builder.build(OWNER, REPO, DIFF, "");

        assertTrue(result.contains("CLAUDE.md"), "应包含 CLAUDE.md 标题");
        assertTrue(result.contains("使用 Java 21"), "应包含文件内容");
    }

    @Test
    @DisplayName("build — 规范文件不存在时静默跳过")
    void specFileNotFound() {
        when(gitHubClient.getRepoFile(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        String result = builder.build(OWNER, REPO, DIFF, "");

        assertTrue(result.contains("unified diff"), "至少应包含 diff");
        assertFalse(result.contains("项目规范文件"), "不应出现规范文件标题");
    }

    // ==================== 完整文件 ====================

    @Test
    @DisplayName("build — 包含变更 Java 文件的完整内容")
    void includesFullFileContent() {
        when(gitHubClient.getRepoFile(anyString(), anyString(), endsWith("CLAUDE.md"), anyString()))
                .thenReturn(null);
        when(gitHubClient.getRepoFile(anyString(), anyString(), endsWith("Foo.java"), anyString()))
                .thenReturn("public class Foo {\n  int x;\n}\n");

        String result = builder.build(OWNER, REPO, DIFF, "");

        assertTrue(result.contains("Foo.java"), "应包含文件名");
        assertTrue(result.contains("int x"), "应包含文件内容");
        assertTrue(result.contains("变更文件完整内容"), "应有章节标题");
    }

    @Test
    @DisplayName("build — 非代码文件不拉取完整内容")
    void skipsNonCodeFiles() {
        String mdDiff = "diff --git a/README.md b/README.md\n"
                + "--- a/README.md\n+++ b/README.md\n+updated\n";

        when(gitHubClient.getRepoFile(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        String result = builder.build(OWNER, REPO, mdDiff, "");

        assertFalse(result.contains("变更文件完整内容"),
                "README.md 不应拉取完整内容");
    }

    // ==================== 工具方法 ====================

    @Test
    @DisplayName("extractFiles — 从 diff 提取文件路径")
    void extractFilesFromDiff() {
        List<String> files = ContextBuilder.extractFiles(DIFF);

        assertEquals(1, files.size());
        assertEquals("src/Foo.java", files.get(0));
    }

    @Test
    @DisplayName("isCodeFile — Java 文件返回 true")
    void isCodeFileJava() {
        assertTrue(ContextBuilder.isCodeFile("src/Foo.java"));
        assertTrue(ContextBuilder.isCodeFile("app.py"));
        assertTrue(ContextBuilder.isCodeFile("lib/index.ts"));
    }

    @Test
    @DisplayName("isCodeFile — 非代码文件返回 false")
    void isCodeFileNonCode() {
        assertFalse(ContextBuilder.isCodeFile("README.md"));
        assertFalse(ContextBuilder.isCodeFile("config.yml"));
        assertFalse(ContextBuilder.isCodeFile("data.json"));
    }

    @Test
    @DisplayName("trimLines — 超过上限时截断并标注")
    void trimLinesWhenExceeds() {
        StringBuilder longContent = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longContent.append("line ").append(i).append('\n');
        }

        String result = ContextBuilder.trimLines(longContent.toString(), 10);

        assertTrue(result.contains("line 0"), "应包含开头行");
        assertTrue(result.contains("line 9"), "应包含第 10 行");
        assertTrue(result.contains("已截断"), "应标注已截断");
        assertTrue(result.contains("100 行"), "应标注总行数");
        assertFalse(result.contains("line 10"), "不应包含第 11 行");
    }

    @Test
    @DisplayName("trimLines — 未超过上限时完整返回")
    void trimLinesWithinLimit() {
        String content = "line1\nline2\n";
        assertEquals(content, ContextBuilder.trimLines(content, 5));
    }

    // ==================== 组装内容包含 diff ====================

    @Test
    @DisplayName("build — 始终包含 unified diff")
    void alwaysIncludesDiff() {
        when(gitHubClient.getRepoFile(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        String result = builder.build(OWNER, REPO, DIFF, "");

        assertTrue(result.contains("unified diff"), "应包含 diff 标题");
        assertTrue(result.contains("return calc(a, b, c)"), "应包含 diff 内容");
    }
}
