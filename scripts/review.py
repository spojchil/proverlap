#!/usr/bin/env python3
"""
PRoverlap — PR 审查命令行工具（Python 版）

用法: python scripts/review.py <PR_URL> [SERVER_URL]

示例:
  python scripts/review.py https://github.com/owner/repo/pull/1
  python scripts/review.py https://github.com/owner/repo/pull/1 http://localhost:8080
  PROVERLAP_SERVER=https://your-host.com python scripts/review.py <URL>

服务器地址优先级: 命令行参数 > PROVERLAP_SERVER 环境变量 > http://localhost:8080

环境准备:
  pip install -r scripts/requirements.txt

  注意：国内网络环境下，Windows 原生 TLS（SChannel）的握手指纹可能被 GFW/DPI
  阻断。本脚本通过 PyOpenSSL 注入替代系统 TLS，确保连接正常。
"""

import json
import os
import sys

from urllib3.contrib.pyopenssl import inject_into_urllib3

inject_into_urllib3()  # 强制走 OpenSSL，必须放在 import requests 之前

import requests


def review(pr_url: str, server: str) -> None:
    """调用 PRoverlap API 审查指定 PR"""
    print(f">>> PRoverlap 审查中...")
    print(f">>> PR: {pr_url}")
    print(f">>> 服务器: {server}")

    try:
        resp = requests.post(
            f"{server}/api/review",
            json={"prUrl": pr_url},
            verify=False,
            timeout=600,
        )
        result = resp.json()
    except requests.RequestException as e:
        print(f"请求失败: {e}")
        sys.exit(1)
    except json.JSONDecodeError:
        print("响应不是有效的 JSON")
        sys.exit(1)

    if result.get("success"):
        data = result["data"]
        print()
        print("=" * 60)
        print("  审查结果")
        print("=" * 60)
        findings = data.get("findings", "")
        # 处理转义的换行符
        print(
            findings.encode().decode("unicode_escape")
            if "\\n" in findings
            else findings
        )
        print()
        print("=" * 60)
        tier_info = data.get("tier", {})
        tier_code = (
            tier_info.get("code", "N/A")
            if isinstance(tier_info, dict)
            else tier_info
        )
        print(f"审查深度: {tier_code}")
    else:
        print(f"审查失败: {result.get('message', '未知错误')}")
        sys.exit(1)


def main() -> None:
    if len(sys.argv) < 2:
        print("用法: review.py <PR_URL> [SERVER_URL]")
        print("示例: review.py https://github.com/owner/repo/pull/1")
        sys.exit(1)

    pr_url = sys.argv[1]
    server = (
        sys.argv[2]
        if len(sys.argv) > 2
        else os.environ.get(
            "PROVERLAP_SERVER", "http://localhost:8080"
        )
    )
    review(pr_url, server)


if __name__ == "__main__":
    main()
