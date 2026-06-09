package io.github.spojchil.proverlap.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GitHub Webhook 事件关键信息。
 *
 * <p>从完整 Webhook JSON Payload 中提取核心字段，后续审查链路依赖这些字段。 完整 payload 见 <a
 * href="https://docs.github.com/en/webhooks/webhook-events-and-payloads#pull_request">GitHub
 * 文档</a>。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WebhookPayload {

    /** 事件动作：opened / synchronize / reopened */
    private String action;

    /** PR 编号 */
    private Integer prNumber;

    /** 仓库全名，格式 {@code owner/repo} */
    private String fullName;

    /** GitHub App 安装 ID（用于获取访问令牌） */
    private Long installationId;

    /** PR head commit SHA（Check Run 需要） */
    private String commitSha;

    /** PR 标题（用于解析 Conventional Commits 类型，如 "feat: 新增登录"） */
    private String prTitle;

    /** PR 描述（用于生成变更摘要，可能为空） */
    private String prDescription;
}
