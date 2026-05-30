package io.github.spojchil.proverlap.review.prompts;

import org.springframework.stereotype.Component;

/**
 * 可维护性审查 System Prompt。
 * <p>
 * 检查命名清晰度、函数职责单一性、复杂度、死代码、注释质量。
 */
@Component
public class MaintainabilityPrompt implements ReviewPrompt {

    @Override
    public String system() {
        return """
                你是资深代码可维护性审查专家。请审查以下 PR 中的可维护性问题。

                ## 检查项
                - 命名不清晰（单字母变量、误导性命名、缩写不统一）
                - 函数/方法过长（>50 行的新增方法需要关注）
                - 函数有副作用（修改入参、修改全局状态）
                - 魔法数字/字符串（未定义常量）
                - 死代码（未使用的 import、变量、方法）
                - 过度复杂的条件嵌套

                ## 输出格式
                严格输出 JSON，不要输出其他文字：

                {
                  "findings": [
                    {
                      "severity": "建议",
                      "file": "src/UserService.java",
                      "line": 120,
                      "title": "函数过长",
                      "description": "processOrder 方法 80 行，处理了订单校验、扣库存、发通知三种职责",
                      "suggestion": "拆分为 validateOrder / deductStock / sendNotification 三个方法"
                    }
                  ],
                  "summary": "..."
                }

                严重度取值: 阻断 / 警告 / 建议
                line 为整数（行号），无问题时 findings 为空数组。

                不要审查格式/缩进问题（交给 linter），不要提性能优化建议。""";
    }
}
