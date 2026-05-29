# AGENTS.md

本文件是 AI 编码助手的**唯一真相源**。`CLAUDE.md` 内容为 `@AGENTS.md`，不再单独维护。

## 项目概述

PRoverlap — 多模型交叉审查 GitHub Pull Request 的自动化工具。安装为 GitHub App 后，每个 PR 触发时用两个不同模型独立审查同一段代码，共识输出高置信度发现，分歧标注两个视角供人类复核。模型可插拔替换。

**核心差异**：现有方案（Claude Code code-review、Night Market pensive 等）都是单模型审查。PRoverlap 的核心价值在于**交叉验证**——两个模型对同一块代码达成共识 → 高置信度；意见不一致 → 分歧本身比一致更有价值。

## 技术栈

| 类别 | 技术 | 备注 |
|------|------|------|
| 语言 | Java 21 | 主技术栈 |
| 框架 | Spring Boot 4.0.6 | Webhook 接口 + 依赖注入 |
| LLM 集成 | LangChain4j 1.15 | OpenAI 兼容接口，支持任意模型提供商 |
| 异步编排 | CompletableFuture + 自定义编排层 | 多模型并行调用 + 超时控制 |
| 数据库 | PostgreSQL 16 | MyBatis-Plus 3.5.15，替代 JPA
| 缓存 | Redis | 临时审查会话、模型响应缓存 |
| GitHub 集成 | GitHub REST API（自建轻量客户端） | Webhook 接收 + PR diff 拉取 + Review Comment 发布 |
| 部署 | Docker Compose | Java 服务 + PostgreSQL + Redis 一键启动 |

## 项目结构

```
proverlap/
├── proverlap-server/              # 主服务模块
│   └── src/main/java/io/github/spojchil/proverlap/
│       ├── PrReviewApplication.java
│       ├── webhook/              # Webhook 接收 + HMAC 验证
│       ├── tier/                 # Tier 分级器（3 级策略）
│       ├── context/              # 上下文准备（diff 拉取 + 切片 + 规范文件读取）
│       ├── review/               # 审查引擎（多模型并行 + 交叉比对）
│       ├── aggregation/          # 结果聚合（合并 + 去重 + 置信度）
│       ├── output/               # 输出发布（GitHub Review Comment）
│       ├── model/entity|dto|enums/
│       ├── config/               # LLM / GitHub Client / Async 配置
│       └── mapper/                # MyBatis-Plus Mapper
├── docs/
│   ├── project-design.md          # 完整设计（Mermaid 图 + 组件详设）
│   └── decisions.md               # 架构决策记录
├── .github/
│   ├── workflows/ci.yml
│   └── pull_request_template.md
├── .claude/
│   ├── settings.json
│   └── skills/
├── .agents/
│   └── skills/
├── docker-compose.yml
├── pom.xml
├── .gitignore
├── .gitmessage
├── AGENTS.md
├── CLAUDE.md
├── CONTRIBUTING.md
├── LICENSE
└── README.md
```

## 常用命令

```bash
# 构建（跳过测试）
./mvnw clean package -DskipTests

# 测试
./mvnw test

# 启动（开发环境，需要先启动 PostgreSQL + Redis）
docker compose up -d postgres redis
./mvnw spring-boot:run -pl proverlap-server

# 全部启动
docker compose up -d

# 代码风格
./mvnw spotless:check
```

## 代码风格

- Java 代码遵循 Google Java Style Guide（通过 Spotless + google-java-format 自动格式化）
- MyBatis-Plus 的 BaseMapper 提供内置 CRUD，无需 XML。Entity 继承 BaseEntity（createTime / updateTime / deletedAt 自动填充）
- 包结构按**职责**（webhook / review / aggregation 等），而非按**层**（controller / service）
- 配置类放在 `config/`，业务代码各司其职

## 测试规范

- 单元测试覆盖核心逻辑（Tier 分级、交叉比对、去重）
- 集成测试使用 Testcontainers（PostgreSQL + Redis）
- LLM 调用层使用 WireMock 做 mock，不依赖外部服务

## Commit 规范

格式：`type(scope): 中文描述`

| type | 用途 |
|------|------|
| `feat` | 新功能 |
| `fix` | 修 Bug |
| `refactor` | 重构（功能不变） |
| `docs` | 文档 |
| `test` | 测试 |
| `chore` | 依赖、配置、脚本 |
| `perf` | 性能优化 |

- 一个 commit 只做一件事
- 描述用中文，说清楚做了什么

## PR 流程

- feature/fix/refactor → develop（日常功能，Squash merge）
- develop → main（集中发布，Merge commit）
- hotfix → main + cherry-pick 回 develop（紧急修复）
- 合并前自查 diff，确认无调试代码、无密钥泄露

## 个人本地规则

如果仓库根目录存在 `AGENTS.local.md`，在会话开始时读取一次，其内容作为个人规则的**扩展**（不覆盖团队规则）。
