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

## 2026-05-30 · JSON 结构化输出替代 Markdown 正则解析

**背景**：LLM 审查输出原本是 Markdown 格式（`> **阻断** \`文件\` L行号 — 标题`），用正则解析为结构化 Finding。存在三个问题：(1) 模型可能不遵守格式；(2) 正则解析脆弱；(3) 截断时解析失败静默丢失发现。

**决策**：全部 Prompt 输出统一为 JSON 格式 `{"findings":[...]}`，通过 `response_format: json_object` 确保合规。`FindingParser` 用 Jackson `readTree()` 替代正则。

**原因**：
- DeepSeek / mimo 均支持 OpenAI 兼容的 `response_format: json_object`
- JSON 结构化输出比正则解析可靠一个数量级
- 即使截断也可尝试部分解析

**影响**：`FindingParser` 从 98 行正则逻辑缩减为 68 行 Jackson 解析。`LLMConfig` 两个 builder 均加 `.responseFormat(ResponseFormat.JSON)`。

---

## 2026-05-30 · 维度 × 模型矩阵 — PR 类型驱动审查维度激活

**背景**：不同 PR 类型（feat/fix/perf/docs...）的审查需求不同。fix PR 无需审查设计维度，docs PR 无需任何代码审查。全维度全模型对文档 PR 是资源浪费。

**决策**：`DimensionReviewer` 维护一个硬编码的维度×模型矩阵：`feat`→5 维度（2 个双模型 CV），`fix`→2 维度（全部 CV），`docs`→0。PR 标题解析 Conventional Commits 前缀自动选择。

**原因**：
- PR 标题规范度高（大部分项目遵循 Conventional Commits），解析成本为零
- 矩阵内聚在 `DimensionReviewer` 中，新增 PR 类型只需加一行 Map entry
- 安全+正确性始终双模型 CV，其余维度单模型覆盖——每一分钱都花在刀刃上

**影响**：`DimensionReviewer.buildMatrix()` 定义 10 种 PR 类型的维度映射。

---

## 2026-05-30 · 阻断判定从文本搜索改为结构化计数

**背景**：`BLOCK_ON_FINDINGS` 模式原本用 `formattedOutput.contains("**阻断**")` 判断是否有阻断级发现。Prompt 模板中的 JSON 示例 `"severity": "阻断"` 导致**永远误判为 failure**。

**决策**：`ReviewOutcome` 记录从 `List<Finding>` 直接统计的阻断数。`BLOCK_UNTIL_REVIEWED` 始终 success，`BLOCK_ON_FINDINGS` 仅 `blockingCount > 0` 时 failure。

**原因**：
- `DimensionResult.findings` 已包含结构化 Finding 对象，直接统计零成本
- 删除 `determineConclusion()` 方法和正则代码，简化架构
- `BLOCK_UNTIL_REVIEWED` 的语义是"审查跑过就放行"，不应检查阻断

**影响**：`ReviewOrchestrator` 新增 `ReviewOutcome` record。`ResultAggregator` 返回 `AggregationResult(text, blockingCount)`。

---

## 2026-05-30 · 双认证渠道 — Installation Token + PAT

**背景**：API 模式原先必须配置完整的 GitHub App（App ID + 私钥 + Installation ID），门槛过高。用户只想试一把公开仓库的审查，不应需要注册 GitHub App。

**决策**：`GitHubClient` 支持两个认证渠道：(1) GitHub App Installation Token（Webhook 模式，自动注入）；(2) Personal Access Token（API 模式，配置 `GITHUB_TOKEN` 即可）。渠道 1 失败自动回退渠道 2。

**原因**：
- PAT 生成只需 30 秒（Settings → Developer settings → Tokens），门槛极低
- 公开仓库 PAT 仅需 `public_repo` 权限，零安全风险
- Installation Token 仍是 Webhook 模式首选（无需用户手动配置）

**影响**：`.env.example` 分为"方式一 PAT"和"方式二 GitHub App"。`GitHubClient` 所有 API 方法均有双通道重载。

---

## 模板

```markdown
### YYYY-MM-DD · 决策标题

**背景**：为什么要做这个决策
**决策**：选了什么方案
**原因**：为什么选 A 而不是 B
**影响**：这个决策对后续开发的影响
```
