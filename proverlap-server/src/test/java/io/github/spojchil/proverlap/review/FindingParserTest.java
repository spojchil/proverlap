package io.github.spojchil.proverlap.review;

import static org.junit.jupiter.api.Assertions.*;

import io.github.spojchil.proverlap.model.dto.Finding;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** FindingParser JSON 解析单元测试。 */
@DisplayName("FindingParser JSON 解析单元测试")
class FindingParserTest {

    private final FindingParser parser = new FindingParser();

    @Test
    @DisplayName("parse — 解析单个阻断发现")
    void parseSingleBlocking() {
        String json =
                """
                {
                  "findings": [
                    {"severity":"阻断","file":"src/AuthService.java","line":3,
                     "title":"硬编码敏感密钥","description":"ADMIN_KEY 被硬编码","suggestion":"改用环境变量"}
                  ],
                  "summary": "发现 1 个安全问题"
                }""";

        List<Finding> findings = parser.parse(json, "modelA");

        assertEquals(1, findings.size());
        Finding f = findings.get(0);
        assertEquals("阻断", f.getSeverity());
        assertEquals("src/AuthService.java", f.getFile());
        assertEquals(3, f.getLine());
        assertEquals("硬编码敏感密钥", f.getTitle());
        assertTrue(f.getDescription().contains("ADMIN_KEY"));
        assertTrue(f.getSuggestion().contains("环境变量"));
        assertEquals("modelA", f.getModelSource());
    }

    @Test
    @DisplayName("parse — 解析多个发现")
    void parseMultipleFindings() {
        String json =
                """
                {
                  "findings": [
                    {"severity":"阻断","file":"src/Foo.java","line":1,"title":"问题一","description":"描述一","suggestion":"修复一"},
                    {"severity":"警告","file":"src/Bar.java","line":2,"title":"问题二","description":"描述二","suggestion":"修复二"}
                  ],
                  "summary": "发现 2 个问题"
                }""";

        List<Finding> findings = parser.parse(json, "modelB");

        assertEquals(2, findings.size());
        assertEquals("阻断", findings.get(0).getSeverity());
        assertEquals("问题一", findings.get(0).getTitle());
        assertEquals("警告", findings.get(1).getSeverity());
        assertEquals("问题二", findings.get(1).getTitle());
    }

    @Test
    @DisplayName("parse — 空 findings 数组返回空列表")
    void parseEmptyFindings() {
        String json =
                """
                {
                  "findings": [],
                  "summary": "未发现安全问题"
                }""";

        List<Finding> findings = parser.parse(json, "modelA");
        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("parse — LLM 包裹 markdown 代码块仍可解析")
    void parseWithMarkdownWrapper() {
        String text =
                """
                以下是审查结果：

                ```json
                {
                  "findings": [
                    {"severity":"警告","file":"src/Foo.java","line":1,"title":"标的","description":"描述","suggestion":"建议"}
                  ],
                  "summary": ""
                }
                ```""";

        List<Finding> findings = parser.parse(text, "modelA");
        assertEquals(1, findings.size());
        assertEquals("标的", findings.get(0).getTitle());
    }

    @Test
    @DisplayName("parse — 非 JSON 文本返回空列表")
    void parseInvalidJson() {
        List<Finding> findings = parser.parse("未发现安全问题。", "modelA");
        assertTrue(findings.isEmpty());
    }

    @Test
    @DisplayName("parse — null 或空字符串返回空列表")
    void parseNullOrBlank() {
        assertTrue(parser.parse(null, "modelA").isEmpty());
        assertTrue(parser.parse("", "modelA").isEmpty());
        assertTrue(parser.parse("   ", "modelA").isEmpty());
    }
}
