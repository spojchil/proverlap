package io.github.spojchil.proverlap.review;

import io.github.spojchil.proverlap.model.dto.Finding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FindingParser Markdown 解析单元测试。
 */
@DisplayName("FindingParser Markdown 解析单元测试")
class FindingParserTest {

    private final FindingParser parser = new FindingParser();

    @Test
    @DisplayName("parse — 解析单个阻断发现")
    void parseSingleBlocking() {
        String text = """
                > **阻断** `src/AuthService.java` L3 — 硬编码敏感密钥
                > ADMIN_KEY 被直接赋值为明文密钥
                > 建议: 将密钥移出代码""";

        List<Finding> findings = parser.parse(text, "modelA");

        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals("阻断", f.getSeverity());
        assertEquals("src/AuthService.java", f.getFile());
        assertEquals(3, f.getLine());
        assertEquals("硬编码敏感密钥", f.getTitle());
        assertTrue(f.getDescription().contains("ADMIN_KEY"));
        assertTrue(f.getSuggestion().contains("将密钥移出代码"));
        assertEquals("modelA", f.getModelSource());
    }

    @Test
    @DisplayName("parse — 解析多个发现")
    void parseMultipleFindings() {
        String text = """
                > **阻断** `src/Foo.java` L1 — 问题一
                > 描述一
                > 建议: 修复一

                > **警告** `src/Bar.java` L2 — 问题二
                > 描述二
                > 建议: 修复二""";

        List<Finding> findings = parser.parse(text, "modelB");

        assertEquals(2, findings.size());
        assertEquals("阻断", findings.get(0).getSeverity());
        assertEquals("问题一", findings.get(0).getTitle());
        assertEquals("警告", findings.get(1).getSeverity());
        assertEquals("问题二", findings.get(1).getTitle());
    }

    @Test
    @DisplayName("parse — 无发现时返回空列表")
    void parseEmpty() {
        List<Finding> findings = parser.parse("未发现安全问题。", "modelA");
        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("parse — 缺少严重度标记的行被跳过")
    void parseMalformedLine() {
        String text = """
                > 这不是一个发现
                > **阻断** `src/Foo.java` L1 — 正常发现
                > 描述""";

        List<Finding> findings = parser.parse(text, "modelA");
        assertEquals(1, findings.size());
        assertEquals("正常发现", findings.get(0).getTitle());
    }

    @Test
    @DisplayName("parse — 仅标题无描述时 title 和 description 相同")
    void parseTitleOnly() {
        String text = "> **建议** `src/Baz.java` L5 — 变量命名不规范";

        List<Finding> findings = parser.parse(text, "modelA");
        assertEquals(1, findings.size());
        assertEquals("变量命名不规范", findings.get(0).getTitle());
    }

    @Test
    @DisplayName("parse — null 或空字符串返回空列表")
    void parseNullOrBlank() {
        assertTrue(parser.parse(null, "modelA").isEmpty());
        assertTrue(parser.parse("", "modelA").isEmpty());
        assertTrue(parser.parse("   ", "modelA").isEmpty());
    }
}
