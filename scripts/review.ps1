# ============================================================
# PRoverlap — PR 审查命令行工具 (PowerShell)
# 用法: .\review.ps1 <PR_URL> [SERVER_URL]
# ============================================================
# 示例:
#   .\review.ps1 https://github.com/owner/repo/pull/1
#   .\review.ps1 https://github.com/owner/repo/pull/1 http://localhost:8080
# ============================================================

param(
    [Parameter(Mandatory=$true)]
    [string]$PrUrl,
    # 优先级: 命令行参数 > 环境变量 > 默认本地地址
    [string]$Server = if ($env:PROVERLAP_SERVER) { $env:PROVERLAP_SERVER } else { "http://localhost:8080" }
)

Write-Host ">>> PRoverlap 审查中..." -ForegroundColor Cyan
Write-Host ">>> PR: $PrUrl"

$body = @{ prUrl = $PrUrl } | ConvertTo-Json
$response = Invoke-RestMethod -Uri "$Server/api/review" -Method Post -Body $body -ContentType "application/json"

if ($response.success) {
    $data = $response.data
    Write-Host ""
    Write-Host "============================================"
    Write-Host "  审查结果 — $($data.owner)/$($data.repo) #$($data.prNumber)"
    Write-Host "  审查深度: $($data.tier)"
    Write-Host "============================================"
    Write-Host ""
    Write-Host $data.findings
    Write-Host ""
    Write-Host "============================================"
} else {
    Write-Host "审查失败: $($response.errorCode) — $($response.message)" -ForegroundColor Red
    exit 1
}
