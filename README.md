# PRoverlap

多模型交叉审查 GitHub Pull Request —— 分歧即信号，共识即跳过。

[![Java](https://img.shields.io/badge/Java-21-blue)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.6-green)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

## 快速开始

```bash
# 1. 克隆
git clone https://github.com/spojchil/proverlap.git
cd proverlap

# 2. 配置环境变量
cp .env.example .env
# 编辑 .env：填入 GitHub App 凭证 + 模型 A / 模型 B 的 API Key

# 3. 一键启动
docker compose up -d
```

## 核心特性

- **双模型交叉审查** — 两个不同模型独立审查同一段代码，交叉比对输出置信度
- **三级分级策略** — 根据 diff 大小和文件敏感度自动选择审查深度（Tier 1/2/3）
- **四维度覆盖** — 安全、逻辑正确性、风格规范、PR 元信息
- **行级 Review Comment** — 发现直接贴到 PR 对应文件和行号上
- **仓库规范感知** — 自动读取 CLAUDE.md / CONTRIBUTING.md / .editorconfig，生成项目专属检查清单

## 架构

```mermaid
graph TD
    GitHub[GitHub Webhook<br/>PR opened / synchronize] --> Controller[WebhookController<br/>签名验证 + 事件解析]

    Controller --> Tier[TierClassifier 分级器<br/>diff 大小 + 文件敏感度]

    Tier -->|Tier 1<br/>diff &lt; 50 行| Fast[快速通道<br/>仅 PR 元信息检查]
    Tier -->|Tier 2<br/>50~500 行| Standard[标准审查<br/>安全 + 风格 + 元信息]
    Tier -->|Tier 3<br/>&gt; 500 行 / 敏感文件| Deep[深度审查<br/>全维度双模型]

    Standard --> Context[ContextPreparer 上下文准备]
    Deep --> Context

    Context --> DiffFetcher[GitHub API 拉 PR diff]
    Context --> SpecReader[读取仓库规范文件<br/>CLAUDE.md / CONTRIBUTING.md / .editorconfig]
    Context --> Slicer[DiffSlicer 按文件/函数边界切片]
    Context --> Checklist[ChecklistGenerator<br/>动态检查清单]

    subgraph Engine [多模型并行审查引擎]
        direction TB
        Sec[安全维度<br/>模型 A + 模型 B]
        Logic[逻辑维度<br/>模型 A + 模型 B]
        Style[风格维度<br/>模型 A]
        Meta[元信息维度<br/>模型 A]
    end

    Checklist --> Engine

    subgraph Cross [交叉比对]
        CrossValidator[同维度两个模型的结果比对<br/>一致 → 高置信<br/>分歧 → 双视角标注]
    end

    Engine --> Cross

    Cross --> Aggregation[ResultMerger 合并 + Deduplicator 去重<br/>按文件/行号归并 · 按严重度排序]

    Aggregation --> Publisher[ReviewPublisher<br/>GitHub Review Comment 行级注释<br/>+ PR Summary Comment]

    Publisher --> GitHub

    style Fast fill:#e8f5e9
    style Standard fill:#fff3e0
    style Deep fill:#fce4ec
```

## 技术栈

Java 21 · Spring Boot 4.0.6 · LangChain4j 1.15 · PostgreSQL 16 · Redis · Docker Compose

## 项目结构

```
├── proverlap-server/         # 主服务模块
├── docs/
│   ├── project-design.md    # 完整设计文档（Mermaid 图 + 详设）
│   └── decisions.md         # 架构决策记录
├── .github/                 # CI + PR 模板
├── docker-compose.yml       # 一键启动
├── pom.xml
└── README.md
```

## 文档

| 文档 | 说明 |
|------|------|
| [贡献指南](CONTRIBUTING.md) | 分支策略、Commit 规范、PR 流程 |
| [项目设计](docs/project-design.md) | 完整设计文档：架构图、时序图、组件详设、数据模型、API |
| [架构决策](docs/decisions.md) | 关键选型理由与演化记录 |

## 许可证

[MIT License](LICENSE)
