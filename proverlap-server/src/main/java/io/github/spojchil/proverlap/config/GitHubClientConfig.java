package io.github.spojchil.proverlap.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * GitHub API 客户端配置。
 *
 * <p>创建 {@link GitHubClient} Bean，底层使用 Spring {@link RestClient} 发送 HTTP 请求。
 */
@Configuration
public class GitHubClientConfig {

    @Bean
    public GitHubClient gitHubClient(GitHubProperties gitHubProperties) {
        return new GitHubClient(RestClient.builder(), gitHubProperties);
    }
}
