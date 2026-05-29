#!/bin/bash
# ============================================================
# PRoverlap — PR 审查命令行工具
# 用法: ./review.sh <PR_URL> [SERVER_URL]
# ============================================================
# 示例:
#   ./review.sh https://github.com/owner/repo/pull/1
#   ./review.sh https://github.com/owner/repo/pull/1 http://localhost:8080
# ============================================================

set -e

PR_URL="${1:?Usage: $0 <PR_URL> [SERVER_URL]}"
# 优先级: 命令行参数 > 环境变量 > 默认本地地址
SERVER="${2:-${PROVERLAP_SERVER:-http://localhost:8080}}"

echo ">>> PRoverlap 审查中..."
echo ">>> PR: $PR_URL"

RESPONSE=$(curl -s -X POST "$SERVER/api/review" \
    -H "Content-Type: application/json" \
    -d "{\"prUrl\": \"$PR_URL\"}")

SUCCESS=$(echo "$RESPONSE" | grep -o '"success":\s*true' || true)

if [ -n "$SUCCESS" ]; then
    echo ""
    echo "============================================"
    echo "  审查结果"
    echo "============================================"
    # 提取并格式化 findings 字段
    echo "$RESPONSE" | sed 's/.*"findings":"//;s/","tier.*//;s/\\n/\n/g;s/\\"/"/g'
    echo ""
    echo "============================================"
    # 提取 Tier
    TIER=$(echo "$RESPONSE" | grep -o '"code":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "审查深度: $TIER"
else
    echo "审查失败:"
    echo "$RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$RESPONSE"
    exit 1
fi
