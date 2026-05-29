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

- LangChain4j 的 ChatLanguageModel 接口抽象屏蔽了不同提供商的差异。
- 所有兼容 OpenAI 接口格式的模型（DeepSeek、智谱、Qwen 等）共用一个 `open-ai` 模块即可。
- 1.15 是最新稳定版，社区活跃度高于 Spring AI。
- 放弃 Spring AI：对国产模型适配不如 LangChain4j 社区模块丰富。

**影响**：所有 LLM 调用走统一的 `ChatLanguageModel` 接口。

---

## 模板

```markdown
### YYYY-MM-DD · 决策标题

**背景**：为什么要做这个决策
**决策**：选了什么方案
**原因**：为什么选 A 而不是 B
**影响**：这个决策对后续开发的影响
```
