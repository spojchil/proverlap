package io.github.spojchil.proverlap.model.dto;

import lombok.Builder;
import lombok.Data;

/**
 * GitHub Webhook 事件关键信息。
 * <p>
 * 从完整 Webhook JSON Payload 中提取核心字段，后续审查链路依赖这些字段。
 * 完整 payload 见
 * <a href="https://docs.github.com/en/webhooks/webhook-events-and-payloads#pull_request">GitHub 文档</a>。
 */
@Data
@Builder
public class WebhookPayload {

    /** 事件动作：opened / synchronize / reopened */
    private String action;

    /** PR 编号 */
    private Integer prNumber;

    /** 仓库全名，格式 {@code owner/repo} */
    private String fullName;

    /** GitHub App 安装 ID（用于获取访问令牌） */
    private Long installationId;
}
