# TODO

> 更新：2026-05-30 · v1.0.0

## 已完成（13 PR）

| PR | 内容 | 状态 |
|:--:|------|:--:|
| #2 | 基础设施配置：LangChain4j + GitHub Client + Async | ✅ |
| #3 | Webhook 接收 + HMAC-SHA256 验签 | ✅ |
| #4 | 审查编排器：diff → LLM → Review Comment | ✅ |
| #5 | Docker 多阶段构建 + docker-compose 部署 | ✅ |
| #6 | 端到端 Bug 修复 | ✅ |
| #7 | Tier 分级器 | ✅ |
| #8 | API 同步审查端点 + CLI 脚本 | ✅ |
| #9 | PAT Token 多认证渠道 | ✅ |
| #10 | 上下文准备：完整文件 + 规范文件 | ✅ |
| #11 | Check Runs + 三级审查模式 + 私钥重构 | ✅ |
| #12 | 双模型交叉验证：FindingParser + CrossValidator | ✅ |
| #13 | 多维度审查调度 + JSON 结构化 + 结果聚合 | ✅ |

## 待做

| 编号 | 内容 | 说明 |
|:--:|------|------|
| P7 | Agent 间讨论 | 分歧时启动讨论轮：把双方的发现和理由发给两个 LLM，互相评论对方观点 |
| P8 | diff 切片 | 大 diff（>500 行）按文件/函数边界分块审查 |
| D1 | 审查日志持久化 | 写入 review_task 等表，支持回溯统计 |
| E1 | 检查清单生成 | 从 CONTRIBUTING.md 动态提取规则 |
| E2 | 语言感知 | 自动识别 PR 语言，注入对应 best practice |
| E3 | 模型性能对比 | 长期追踪各模型准确率和误报率 |
| S1 | Demo 视频 | 用项目自身 PR 做演示 |
