# PRoverlap

多模型交叉审查 GitHub Pull Request —— 分歧即信号，共识即跳过。

[![CI/CD](https://github.com/spojchil/proverlap/actions/workflows/ci.yml/badge.svg)](https://github.com/spojchil/proverlap/actions/workflows/ci.yml)
[![Java](https://img.shields.io/badge/Java-21-blue)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.6-green)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-MIT-yellow)](LICENSE)

## 目录

- [快速开始](#快速开始)
- [核心特性](#核心特性)
- [工作原理](#工作原理)
- [审查流程](#审查流程)
- [技术栈](#技术栈)
- [项目结构](#项目结构)
- [文档](#文档)
- [许可证](#许可证)

## 配置

### 环境变量

```bash
# 至少配置以下三项：
# 方式一（推荐·API 模式）：只需一个 PAT Token
GITHUB_TOKEN=ghp_xxx          # GitHub Settings → Developer settings → Personal access tokens
                               # 公开仓库勾选 public_repo，私有仓库勾选 repo

# 方式二（Webhook 模式）：注册 GitHub App
GITHUB_APP_ID=3902868
GITHUB_PRIVATE_KEY=-----BEGIN RSA PRIVATE KEY-----...
GITHUB_WEBHOOK_SECRET=your-secret
GITHUB_INSTALLATION_ID=12345678  # App 安装到仓库后的安装 ID

# 两个模型 API Key（至少配置一个）
MODEL_A_BASE_URL=https://api.deepseek.com/v1
MODEL_A_API_KEY=sk-xxx
MODEL_A_MODEL=deepseek-v4-flash

MODEL_B_BASE_URL=...
MODEL_B_API_KEY=...
MODEL_B_MODEL=...
```

### GitHub App 注册（Webhook 模式）

1. `GitHub Settings → Developer settings → GitHub Apps → New GitHub App`
2. 配置项：
   - **Webhook URL**: `https://你的域名/webhook/github`
   - **Webhook Secret**: 随机生成
   - **Permissions**: `Pull requests` → Read & Write, `Contents` → Read-only
   - **Events**: 勾选 `Pull request`
3. 创建后：**General → Private keys → Generate**，下载 `.pem` 文件，内容填入 `GITHUB_PRIVATE_KEY`
4. **Install App → 选择仓库**，安装后 URL 末尾的数字即 `GITHUB_INSTALLATION_ID`

> **提示**：如果只想试用审查功能，无需注册 GitHub App。配置 `GITHUB_TOKEN` 即可通过 CLI 或 API 使用。

## 快速开始

```bash
# 方式一：拉取预构建镜像（推荐）
docker pull ghcr.io/spojchil/proverlap:latest
cp .env.example .env  # 编辑填入 GITHUB_TOKEN + 模型 A/B 的 API Key
docker compose up -d

# 方式二：从源码构建
git clone https://github.com/spojchil/proverlap.git
cd proverlap
cp .env.example .env
docker compose up -d
```

```bash
# CLI 审查公开 PR（无需安装 App，四平台脚本）
pip install -r scripts/requirements.txt
python scripts/review.py https://github.com/owner/repo/pull/1   # 推荐
./scripts/review.sh <URL>       # Linux/macOS
review.bat <URL>                # Windows CMD
.\review.ps1 <URL>              # Windows PowerShell
```

> **注意**：国内网络环境下，Python 脚本通过 PyOpenSSL 注入绕过 TLS 指纹阻断。Windows CMD/PowerShell 如遇 TLS 连接失败，请使用 Python 版。

## 核心特性

- **双模型交叉验证** — 两个不同模型独立审查同一段代码，共识=高置信，分歧=双视角标注
- **PR 类型驱动** — 解析 PR 标题（feat/fix/perf/refactor...），自动激活对应审查维度
- **六维度覆盖** — 设计与架构、正确性、安全性、性能、可维护性、测试覆盖
- **三级分级策略** — 根据 diff 大小和文件敏感度自动选择审查深度（Tier 1/2/3）
- **Check Runs 阻塞合并** — 三级模式：仅评论 / 审查完放行 / 有阻断问题阻止合并
- **上下文感知** — 自动读取变更文件的完整内容 + 项目规范文件（CLAUDE.md 等）
- **双入口** — Webhook 自动触发 + `POST /api/review` 同步 API

### 审查模式与 Check Runs

通过 `REVIEW_MODE` 环境变量控制：

| 模式 | 行为 | Check Run |
|------|------|:--:|
| `COMMENT_ONLY` | 审查结果仅贴为 Review Comment，不阻塞合并 | 不创建 |
| `BLOCK_UNTIL_REVIEWED` | 审查完成前 PR 不可合并 | ✅ 审查完即放行 |
| `BLOCK_ON_FINDINGS` | 有阻断级问题时阻止合并 | ✅ 阻断→failure / 无阻断→success |

Check Run 包含完整审查报告，Review Comment 仅贴摘要。开发者可在 PR 的 **Checks** 标签页查看详情。

## 工作原理

PRoverlap 作为 GitHub App 安装到仓库后，每次 PR 事件触发时：

1. **解析 PR 类型** — 从标题提取 Conventional Commits 类型（feat/fix/perf...），自动激活对应审查维度
2. **组装上下文** — 读取变更文件的完整内容 + 项目规范文件（CLAUDE.md 等）+ unified diff
3. **多维度并行审查** — 安全+正确性两个核心维度各用两个不同模型交叉验证；设计、性能、可维护性、测试四个次要维度各用单模型覆盖
4. **交叉比对** — 双模型发现按文件+行号+严重度+标题匹配，输出共识（高置信）/ 分歧（双视角标注）/ 单模型发现
5. **结果输出** — Review Comment 贴摘要、Check Run 放完整报告，支持阻塞合并

## 审查流程

```
PR 事件 / API 请求
  ↓
Tier 分级 → PR 类型解析（feat/fix/perf/...）
  ↓
上下文组装（规范文件 + 变更文件完整内容 + diff）
  ↓
多维度并行审查
  ├── 设计与架构（单模型）
  ├── 正确性（双模型交叉验证）──┐
  ├── 安全性（双模型交叉验证）──┤
  ├── 性能（单模型）            │
  ├── 可维护性（单模型）        │
  └── 测试覆盖（单模型）        │
       ↓                        ↓
  单模型标注                CrossValidator 比对
  "需复核"                  → 共识 / 分歧 / 单模型发现
       ↓                        ↓
  ResultAggregator 去重 → 排序 → 摘要
       ↓
  Review Comment（摘要）+ Check Run（完整报告）
```

## 技术栈

Java 21 · Spring Boot 4.0.6 · LangChain4j 1.15 · PostgreSQL 16 · Redis · Docker Compose

## 项目结构

```
├── proverlap-server/              # 主服务模块
│   └── src/main/java/.../proverlap/
│       ├── webhook/               # Webhook 接收 + HMAC 验签
│       ├── tier/                  # Tier 分级器
│       ├── context/               # 上下文准备（文件 + diff 组装）
│       ├── review/                # 审查引擎（多维度 + 交叉比对 + 格式化）
│       ├── aggregation/           # 结果聚合（去重 + 排序 + 标注）
│       ├── output/                # API 审查端点
│       ├── config/                # LLM / GitHub / 异步配置
│       └── model/                 # DTO / 枚举 / 实体
├── scripts/                       # CLI 脚本（py / bash / bat / ps1）
├── docs/                          # 设计文档 + 决策记录
├── docker-compose.yml
└── README.md
```

## 文档

| 文档 | 说明 |
|------|------|
| [贡献指南](CONTRIBUTING.md) | 分支策略、Commit 规范、PR 流程 |
| [审查维度设计](docs/dimension-design.md) | 六层维度体系 + 维度×模型矩阵 + PR 类型联动 |
| [项目设计](docs/project-design.md) | 完整设计文档 |
| [架构决策](docs/decisions.md) | 关键选型理由与演化记录 |

## 许可证

[MIT License](LICENSE)
