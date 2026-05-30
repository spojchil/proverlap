package io.github.spojchil.proverlap.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * GitHub App 配置属性。
 * <p>
 * 包含 GitHub App 认证和 Webhook HMAC 签名所需的配置。
 * 私钥支持 PKCS#8（BEGIN PRIVATE KEY）和 PKCS#1（BEGIN RSA PRIVATE KEY）两种 PEM 格式。
 */
@Data
@Component
@ConfigurationProperties(prefix = "proverlap.github")
public class GitHubProperties {

    /** GitHub App ID（数字字符串） */
    private String appId;

    /** GitHub App 私钥（PEM 格式完整字符串，含 BEGIN/END 边界标记） */
    private String privateKey;

    /** Webhook HMAC-SHA256 签名密钥 */
    private String webhookSecret;

    /** GitHub App 安装 ID（Webhook 注入或 API 模式手动配置） */
    private Long installationId;

    /** Personal Access Token（公开仓库零配置方案，优先级低于 Installation ID） */
    private String token;

    /** 审查模式：COMMENT_ONLY / BLOCK_UNTIL_REVIEWED / BLOCK_ON_FINDINGS */
    private String reviewMode = "COMMENT_ONLY";
}
