# 审查维度设计

> v1.0.0 · 定义 PRoverlap 的六维度审查体系、维度×模型矩阵、PR 类型联动。

---

## 一、六层审查维度

取自代码审查最佳实践，从高到低排列——越高层反馈越值钱：

| # | 维度 | 关注点 | AI 可审性 | 优先级 |
|:--:|------|------|:--:|:--:|
| 1 | 设计与架构 | 分层破坏、循环依赖、抽象合理性 | ⚠️ 模式匹配 | 最高 |
| 2 | 正确性 | 空指针、越界、并发竞争、死分支、资源泄露 | ✅ 强项 | 高 |
| 3 | 安全性 | SQL 注入、XSS、密钥泄露、认证绕过、路径遍历 | ✅ 强项 | 高 |
| 4 | 性能 | N+1 查询、不必要大对象、阻塞 I/O、锁粒度过大 | ⚠️ 无 profiling 数据 | 中 |
| 5 | 可维护性 | 命名、函数粒度、魔法数字、死代码、条件嵌套 | ✅ 强项 | 中 |
| 6 | 测试覆盖 | 关键路径缺失测试、过度 mock、happy-path-only | ⚠️ 需查看测试代码 | 中 |

**不审查**风格/格式——交给 linter/formatter，CI 不够格不 merge。

---

## 二、维度 × 模型矩阵

PRoverlap 有两个模型插槽（modelA / modelB）。双模型交叉验证（CV）仅用于**安全 + 正确性**——这两个是 LLM 强项且误报后果严重。其余维度单模型覆盖。

| PR 类型 | 设计 | 正确性 | 安全 | 性能 | 可维护性 | 测试 |
|------|:--:|:--:|:--:|:--:|:--:|:--:|
| `feat` | ✅ | ✅(CV) | ✅(CV) | — | ✅ | ✅ |
| `fix` | — | ✅(CV) | ✅(CV) | — | — | — |
| `perf` | — | ✅(CV) | — | ✅ | — | — |
| `refactor` | — | ✅(CV) | — | — | ✅ | — |
| `docs/style/chore/build/ci` | — | — | — | — | — | — |
| `test` | — | — | — | — | — | ✅ |

CV = 双模型交叉验证。PR 类型从标题解析 Conventional Commits 前缀（`^(feat|fix|perf|...)`），未匹配回退 `feat`。

---

## 三、Tier 联动

| Tier | 维度策略 |
|:--:|------|
| T1 | 只保留 1 个单模型维度（第一个 modelCount=1 的） |
| T2 | 全维度 × 矩阵，跳过 design |
| T3 | 全维度 × 矩阵 |

---

## 四、Prompt 体系

```
review/prompts/
├── ReviewPrompt.java              # 维度 Prompt 统一接口
├── SecurityPrompt.java            # 安全审查
├── CorrectnessPrompt.java         # 正确性审查
├── DesignPrompt.java              # 设计与架构审查
├── PerformancePrompt.java         # 性能审查
├── MaintainabilityPrompt.java     # 可维护性审查
└── TestCoveragePrompt.java        # 测试覆盖审查
```

全部 Prompt 输出统一 JSON 格式 `{"findings":[...]}`，通过 `response_format: json_object` 确保结构化输出。

---

## 五、审查模式

| 模式 | 环境变量 | Check Run | 阻塞行为 |
|------|------|:--:|------|
| `COMMENT_ONLY` | 默认 | 不创建 | 仅评论 |
| `BLOCK_UNTIL_REVIEWED` | `REVIEW_MODE=BLOCK_UNTIL_REVIEWED` | ✅ | 审查完成前阻塞合并，无阻断级判断 |
| `BLOCK_ON_FINDINGS` | `REVIEW_MODE=BLOCK_ON_FINDINGS` | ✅ | 有阻断级发现时标记 failure，阻止合并 |

阻断判定：从 `DimensionResult.findings` 列表直接统计 `severity="阻断"` 的数量，>0 即 failure。
