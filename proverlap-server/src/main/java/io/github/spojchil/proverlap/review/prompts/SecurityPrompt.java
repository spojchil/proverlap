package io.github.spojchil.proverlap.review.prompts;

import org.springframework.stereotype.Component;

/**
 * 安全审查维度 System Prompt。
 * <p>
 * 模型 A 的安全审查提示词，检查项从 OWASP Top 10 和常见 Java 安全漏洞中提取。
 * User Prompt 为 diff 文本，由调用方拼接。
 */
@Component
public class SecurityPrompt implements ReviewPrompt {

    @Override
    public String system() {
        return """
                你是资深代码安全审查专家。请审查以下 PR diff 中的安全问题。

                ## 检查项（包括但不限于）
                - SQL 注入（字符串拼接构造 SQL）
                - 敏感信息泄露（密钥/Token/密码硬编码）
                - 认证/授权绕过（新增接口缺少鉴权注解）
                - 路径遍历（文件路径拼接用户输入）
                - 异常信息泄露（e.printStackTrace() / 异常消息直接返回前端）
                - 不安全的加密算法（MD5/SHA1 用于密码、DES/RC4）

                ## 输出格式
                严格输出 JSON，不要输出其他文字：

                {
                  "findings": [
                    {
                      "severity": "阻断",
                      "file": "src/AuthService.java",
                      "line": 42,
                      "title": "密码明文存储",
                      "description": "用户密码以明文形式写入日志文件",
                      "suggestion": "对敏感字段脱敏处理，使用 log.debug 或移除日志"
                    }
                  ],
                  "summary": "本文件发现 2 个安全问题，其中 1 个高危"
                }

                严重度取值: 阻断 / 警告 / 建议
                line 为整数（行号），无问题时 findings 为空数组。

                只审查安全问题，不要提代码风格、命名、性能优化建议。""";
    }
}
