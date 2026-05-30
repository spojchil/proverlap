# 架构决策记录

> 只记"为什么"，不记流水账。每条标注日期。

## 2026-05-29 · 项目初始化

**背景**：需要选择一个能快速验证多模型交叉审查概念的启动框架。
**决策**：Java 21 + Spring Boot 4.0.6 + Maven 单模块（proverlap-server）。
**原因**：

- Java 21 是 LTS，Spring Boot 4.x 原生支持 virtual thread，适合 I/O 密集型（多 LLM API 并发调用）。
- 初期功能集中，单模块够用。后续如果加 Web 面板，再拆为多模块。
- 放弃 Spring Cloud 微服务架构：项目本质是单点服务 + 事件驱动，不需要服务发现/网关。

**影响**：所有代码在 `proverlap-server` 下，包结构按职责拆分（webhook / review / aggregation / output）。

---

## 2026-05-29 · LLM 框架选型

**背景**：需要同时调用多个不同提供商的模型（通过 OpenAI 兼容接口统一对接）。
**决策**：LangChain4j 1.15（BOM 管理版本）+ open-ai 兼容模块。
**原因**：

- LangChain4j 的 ChatModel 接口抽象屏蔽了不同提供商的差异。
- 所有兼容 OpenAI 接口格式的模型（DeepSeek、智谱、Qwen 等）共用一个 `open-ai` 模块即可。
- 1.15 是最新稳定版，社区活跃度高于 Spring AI。
- 放弃 Spring AI：对国产模型适配不如 LangChain4j 社区模块丰富。

**影响**：所有 LLM 调用走统一的 `ChatModel` 接口。

---

## 2026-05-29 · 不引入外部 JWT 库

**背景**：GitHub App 认证需要生成 RS256 JWT，社区标准方案是引入 jjwt 或 nimbus-jose-jwt。
**决策**：自建 `java.security.Signature` 完成 RS256 签名，不引入外部 JWT 库。
**原因**：
- GitHub App JWT 结构极简（header + iat/exp/iss payload + RS256 签名），三行 `java.security` 代码即可。
- jjwt（~300 KB）和 nimbus（~800 KB）对这个小场景是过度依赖，每增加一个依赖就多一重 CVE 追踪负担。
- 自建 DER 解析器也不到 30 行，无运行时依赖风险。

**影响**：`GitHubClient.generateJwt()` 和 `parsePrivateKey()` 中包含 DER 解析代码。

---

## 2026-05-29 · 不引入 GitHub API 第三方库

**背景**：社区标准是 org.kohsuke.github（GitHub API for Java），提供类型安全的 API 包装。
**决策**：自建轻量 REST 客户端，使用 Spring `RestClient` 直接调用 GitHub REST API。
**原因**：
- PRoverlap 只需要 3 个端点（拉 diff、读文件、发 comment），不是全功能 GitHub 客户端。
- org.kohsuke.github 约 600 KB，为 3 个 API 调用引入，依赖收益极低。
- 自定义 JWT 生成 + 安装令牌缓存在自建客户端中更可控。

**影响**：`GitHubClient` 类约 200 行，后续如需更多端点，按需添加即可。

---

## 2026-05-29 · 不使用 LangChain4j Spring Boot Starter

**背景**：LangChain4j 提供 `langchain4j-open-ai-spring-boot-starter`，通过 `application.yml` 配置即可自动创建 ChatModel Bean。
**决策**：手动 `OpenAiChatModel.builder()` 创建 Bean，不使用 Starter 自动配置。
**原因**：
- Starter 只创建一个 ChatModel Bean，PRoverlap 需要两个（modelA + modelB），Starter 无法满足。
- 手动构造可精细控制每个模型的 temperature、maxTokens、timeout 等参数。
- 减少了 Starter 自动配置在双模型场景下的意外行为。

**影响**：`LLMConfig` 中两个 `@Bean("modelA")` / `@Bean("modelB")` 方法承担模型创建职责。

---

## 模板

```markdown
### YYYY-MM-DD · 决策标题

**背景**：为什么要做这个决策
**决策**：选了什么方案
**原因**：为什么选 A 而不是 B
**影响**：这个决策对后续开发的影响
```
