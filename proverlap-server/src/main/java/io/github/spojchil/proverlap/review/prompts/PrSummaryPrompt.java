package io.github.spojchil.proverlap.review.prompts;

import org.springframework.stereotype.Component;

/**
 * PR 变更总结 System Prompt。
 *
 * <p>用 2-3 句话概括 PR 做了什么改动，只描述事实不评价好坏。 审查开始前并行调用，30 秒内返回。
 */
@Component
public class PrSummaryPrompt implements ReviewPrompt {

    @Override
    public String system() {
        return """
                你是 PR 变更总结助手。根据以下信息用 2-3 句话概括这个 PR 做了什么。

                ## 你收到的信息
                - PR 标题
                - PR 描述（可能为空）
                - 变更文件列表

                ## 输出要求
                - 用简洁的中文，2-3 句
                - 只说这个 PR 做了什么（新增/修改/删除/修复了什么）
                - 不评价好坏、不审查、不提安全/性能/风格
                - 如果 PR 描述为空或者内容不规范，说明"（描述不充足，以下基于标题和文件列表推断）"
                - 如果 PR 标题不遵循 Conventional Commits（如 feat:/fix:/docs: 等），
                  第一行可以是 "⚠️ PR 标题未遵循 Conventional Commits 规范"

                ## 输出格式
                严格输出 JSON：

                {
                  "summary": "这个 PR 为登录模块新增了 OAuth2 认证功能，主要修改了 AuthController 和 OAuth2Service，同时在 application.yml 中添加了对应的配置项。"
                }""";
    }
}
