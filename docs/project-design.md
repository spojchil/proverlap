# PRoverlap — 项目设计文档

> v1.0.0 · 最后更新 2026-05-30

## 一、项目定义

### 一句话

多模型交叉审查 GitHub Pull Request，分歧即信号，共识即跳过。

### 核心差异

现有方案都是一个模型做审查。PRoverlap 的方案：**安全+正确性两个核心维度各用两个不同模型独立审查，交叉比对输出置信度**。共识=高置信，分歧=双视角标注。

### 项目定位

一个**开源 GitHub App**。安装到任何仓库后自动对 PR 进行多维度交叉审查，输出 Review Comment（摘要）+ Check Run（完整报告）。

---

## 二、技术选型

| 类别 | 选型 | 理由 |
|------|------|------|
| 语言 | **Java 21** | Virtual Thread 原生支持，I/O 密集型多 LLM 并发 |
| 框架 | **Spring Boot 4.0.6** | Webhook + DI + 配置管理 |
| LLM 集成 | **LangChain4j 1.15** | OpenAI 兼容接口，一套代码对接多个模型 |
| 异步编排 | **CompletableFuture + Virtual Thread** | 多模型并行 + 超时控制 |
| GitHub | **自建 REST 客户端** | JWT 签名 + PAT 双认证渠道 |
| 数据库 | **PostgreSQL 16 + MyBatis-Plus 3.5** | 审查记录持久化 |
| 缓存 | **Redis** | 会话状态缓存 |
| 部署 | **Docker Compose** | 一键启动全栈 |

### 模型设计

| 角色 | 用途 | 示例 |
|------|------|------|
| 模型 A | 主审查，负责全部维度 | DeepSeek-v4 |
| 模型 B | 交叉验证，安全+正确性双模型比对 | mimo-v2.5 / Claude |

---

## 三、系统架构

### 审查流程

```
Webhook / API 请求
  ↓
Tier 分级（diff 大小 + 文件敏感度）
  ↓
PR 类型解析（feat/fix/perf/refactor...）
  ↓
ContextBuilder（规范文件 + 变更文件完整内容 + diff）
  ↓
DimensionReviewer（维度×模型矩阵调度）
  ├── 双模型 CV: 安全、正确性 ─→ CrossValidator ─→ 共识/分歧/单模型
  └── 单模型: 设计、性能、可维护性、测试
  ↓
ResultAggregator（去重 + 排序 + 摘要）
  ↓
Review Comment（摘要）+ Check Run（完整报告）
```

### 包结构

```
io.github.spojchil.proverlap/
├── ProverlapApplication.java
├── webhook/
│   ├── WebhookController.java        # POST /webhook/github
│   └── WebhookValidator.java         # HMAC-SHA256 验签
├── tier/
│   └── TierClassifier.java           # 三级分级器
├── context/
│   └── ContextBuilder.java           # diff + 完整文件 + 规范文件组装
├── review/
│   ├── ReviewOrchestrator.java       # 审查编排（双入口）
│   ├── DimensionReviewer.java        # 维度 × 模型矩阵调度
│   ├── CrossValidator.java           # 双模型交叉比对
│   ├── FindingParser.java            # JSON 输出解析
│   └── prompts/
│       ├── ReviewPrompt.java          # 维度 Prompt 接口
│       ├── SecurityPrompt.java
│       ├── CorrectnessPrompt.java
│       ├── DesignPrompt.java
│       ├── PerformancePrompt.java
│       ├── MaintainabilityPrompt.java
│       └── TestCoveragePrompt.java
├── aggregation/
│   └── ResultAggregator.java         # 去重 + 排序 + 摘要统计
├── output/
│   └── ReviewController.java         # POST /api/review
├── model/
│   ├── dto/                          # Finding, ReviewResult, WebhookPayload 等
│   ├── enums/                        # TierLevel, ReviewMode
│   └── entity/                       # BaseEntity（MyBatis-Plus）
└── config/
    ├── LLMConfig.java                # ChatModel Bean（双模型）
    ├── GitHubClient.java             # 自建 REST 客户端
    ├── GitHubProperties.java         # GitHub App + PAT 配置
    ├── ModelProperties.java          # 双模型插槽配置
    ├── TierProperties.java           # Tier 阈值配置
    └── AsyncConfig.java              # Virtual Thread 执行器
```

---

## 四、核心组件

### 4.1 Tier 分级器

```
输入: diff 行数 + 变更文件列表
输出: TierLevel (TIER_1 / TIER_2 / TIER_3)

T1（快速通道）: diff ≤ 50 行 且 全为非代码文件
T2（标准审查）: diff 50-500 行，无敏感文件
T3（深度审查）: diff > 500 行 或 含敏感文件（auth/security/sql/migration）
```

### 4.2 ContextBuilder（上下文组装器）

```
输入: owner, repo, diff, branch ref
输出: 拼装好的审查上下文文本

组装顺序:
  1. 项目规范文件（CLAUDE.md / CONTRIBUTING.md / .editorconfig）
  2. 变更文件完整内容（最多 5 个代码文件，单文件 ≤ 2000 行）
  3. PR unified diff（节选 ≤ 40KB）
```

### 4.3 DimensionReviewer（维度 × 模型调度器）

核心：PR 标题 → 解析类型 → 查维度×模型矩阵 → 并行调度。

```
PR 标题 "feat: 新增 OAuth2 登录"
  → parseType → "feat"
  → MATRIX["feat"] → 5 个维度
    ├── 双模型 CV: correctness + security
    └── 单模型: design + maintainability + test

PR 标题 "fix: 修复并发竞态"
  → parseType → "fix"
  → MATRIX["fix"] → 2 个维度
    └── 双模型 CV: correctness + security

PR 标题 "docs: 更新 README"
  → parseType → "docs"
  → MATRIX["docs"] → 空 → 跳过审查
```

维度×模型矩阵（详见 `docs/dimension-design.md`）。

### 4.4 CrossValidator（双模型交叉比对）

```
输入: 模型 A 的 Finding 列表 + 模型 B 的 Finding 列表
匹配规则: 文件相同 + 行号邻近(≤5) + 严重度相同 → 共识
         文件相同 + 行号邻近 + 严重度不同 → 分歧
         仅一侧有发现 → 单模型标注

输出: CrossValidationResult（共识列表 + 分歧对 + A独有 + B独有）
```

### 4.5 ResultAggregator（结果聚合器）

```
输入: 各维度 DimensionResult 列表
处理:
  1. 扁平化 → 收集所有 Finding
  2. 去重（同文件 + 同行±5 + 同严重度 → 保留高置信度）
  3. 排序（阻断 > 警告 > 建议）
  4. 统计摘要

输出: Markdown 审查报告（摘要 + 按维度分节）
```

---

## 五、审查维度

| # | 维度 | 双模型 CV | 说明 |
|:--:|------|:--:|------|
| 1 | 设计与架构 | — | 分层破坏、循环依赖、抽象合理性 |
| 2 | 正确性 | ✅ | 空指针、越界、并发、死分支、资源泄露 |
| 3 | 安全性 | ✅ | SQL 注入、XSS、密钥泄露、认证绕过 |
| 4 | 性能 | — | N+1 查询、不必要大对象、阻塞 I/O |
| 5 | 可维护性 | — | 命名、函数粒度、魔法数字、死代码 |
| 6 | 测试覆盖 | — | 关键路径缺失测试、过度 mock |

**不审查风格/格式** — 交给 linter/formatter，CI 不够格不 merge。

（完整维度×模型矩阵 + PR 类型联动见 `docs/dimension-design.md`）

---

## 六、API 设计

### Webhook（异步）

```
POST /webhook/github
Headers: X-Hub-Signature-256: sha256=xxx
         X-GitHub-Event: pull_request

处理事件:
  pull_request.opened       → 触发审查
  pull_request.synchronize  → 重新审查
  pull_request.reopened     → 重新审查
```

### 审查 API（同步）

```
POST /api/review
Body: { "prUrl": "https://github.com/owner/repo/pull/1" }

Response:
{
  "success": true,
  "data": {
    "owner": "owner",
    "repo": "repo",
    "prNumber": 1,
    "tier": "TIER_3",
    "findings": "## 审查总结\n1 阻断 · 3 警告 · 0 建议..."
  }
}
```

### CLI

```bash
./scripts/review.sh https://github.com/owner/repo/pull/1
```

---

## 七、数据模型

```sql
-- 审查任务
CREATE TABLE review_task (
    id VARCHAR(64) PRIMARY KEY,
    owner VARCHAR(128), repo VARCHAR(128),
    pr_number INTEGER, commit_sha VARCHAR(40),
    tier VARCHAR(10), status VARCHAR(20),
    total_findings INTEGER, blocking_count INTEGER,
    completed_at TIMESTAMP,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at BIGINT NOT NULL DEFAULT 0
);

-- 维度审查结果
CREATE TABLE dimension_result (
    id VARCHAR(64) PRIMARY KEY, task_id VARCHAR(64) NOT NULL,
    dimension VARCHAR(30), model_name VARCHAR(50),
    file_path VARCHAR(512), findings_json JSONB,
    tokens_used INTEGER, latency_ms INTEGER,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at BIGINT NOT NULL DEFAULT 0
);

-- 交叉比对结果
CREATE TABLE cross_validation (
    id VARCHAR(64) PRIMARY KEY, task_id VARCHAR(64) NOT NULL,
    dimension VARCHAR(30), code_block_hash VARCHAR(64),
    consensus VARCHAR(20),
    model_a_name VARCHAR(50), model_b_name VARCHAR(50),
    model_a_confidence DECIMAL(3,2), model_b_confidence DECIMAL(3,2),
    result_json JSONB,
    create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at BIGINT NOT NULL DEFAULT 0
);
```

---

## 八、扩展路线

| 优先级 | 功能 | 说明 |
|:--:|------|------|
| P1 | Agent 间讨论 | 分歧时启动讨论轮，LLM 评审双方结论 |
| P1 | 共识缓存 | 已验证的同类代码块缓存，后续单模型快速验证 |
| P2 | 增量审查 | 只审查新增 commit |
| P2 | 语言感知 | 自动识别 PR 语言，注入对应 best practice |
| P2 | GitLab / Gitee 支持 | 抽象 Git 平台接口层 |
| P3 | Web 管理面板 | 审查历史、统计仪表盘 |
| P3 | 模型性能对比 | 长期追踪各模型准确率/误报率 |
