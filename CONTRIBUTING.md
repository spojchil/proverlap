# 贡献指南

## 分支策略（Git Flow）

```
main              ← 稳定版本，只接受 develop 合并和 hotfix
  └── develop     ← 开发主线
        ├── feature/<slug>   # 功能分支
        ├── fix/<slug>       # Bug 修复
        ├── refactor/<slug>  # 重构
        ├── chore/<slug>     # 工程配置
        └── docs/<slug>      # 文档
```

### 规则

- **永远不在 main 上直接提交**，main 只通过 PR 进入
- **一个功能一个分支**，从 develop 切出，合回 develop
- 功能分支生命周期 ≤ 3 天，超时拆小或 rebase
- hotfix 从 main 切出，修复后合并到 main，然后 cherry-pick 回 develop

### 日常操作

```bash
# 开始新功能
git checkout develop && git pull origin develop
git checkout -b feature/<slug>

# 开发中频繁提交（草稿可以随意）
git commit -m "wip: xxx"

# 功能完成后，整理历史
git rebase -i develop       # squash 草稿 → 干净 commit

# 推送并创建 PR
git push origin feature/<slug>
# 在 GitHub 上创建 PR：feature/<slug> → develop
```

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

使用 `.github/pull_request_template.md` 模板，合并前确认：

- [ ] 本地测试通过
- [ ] diff 自查无问题
- [ ] 无调试代码、密钥残留
- [ ] 相关文档已更新

### 合并策略

| 场景 | 方式 | 原因 |
|------|------|------|
| feature → develop | Squash merge | 保持 develop 历史线性 |
| develop → main | Merge commit | 保留集成节点，方便追溯 |
| hotfix → main | Squash merge | 单次修复，一次提交即可 |

## 版本号（SemVer）

```
v主版本.次版本.修订号

v1.0.0  ← 首次正式发布
v1.1.0  ← 新增功能（向下兼容）
v1.1.1  ← 只修了 Bug
v2.0.0  ← 破坏性变更
```

每次发布在 main 上打 tag：`git tag v1.1.0 && git push origin v1.1.0`

## 文档

| 文档 | 说明 |
|------|------|
| `README.md` | 项目介绍 + 快速开始 |
| `docs/decisions.md` | 架构决策记录（只记"为什么"，不记流水账） |
| `AGENTS.md` | AI 编码助手指令 |
