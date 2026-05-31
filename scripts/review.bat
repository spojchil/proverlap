@echo off
REM ============================================================
REM PRoverlap — PR 审查命令行工具 (CMD)
REM 用法: review.bat <PR_URL> [SERVER_URL]
REM ============================================================
REM 示例:
REM   review.bat https://github.com/owner/repo/pull/1
REM   review.bat https://github.com/owner/repo/pull/1 http://localhost:8080
REM ============================================================

setlocal enabledelayedexpansion

if "%~1"=="" (
    echo Usage: review.bat ^<PR_URL^> [SERVER_URL]
    exit /b 1
)

set PR_URL=%~1
REM 优先级: 命令行参数 > 环境变量 > 默认本地地址
set SERVER=%2
if "%SERVER%"=="" set SERVER=%PROVERLAP_SERVER%
if "%SERVER%"=="" set SERVER=http://localhost:8080

echo ^>^>^> PRoverlap 审查中...
echo ^>^>^> PR: %PR_URL%

set BODY={"prUrl": "%PR_URL%"}

curl -s -X POST "%SERVER%/api/review" ^
    -H "Content-Type: application/json" ^
    -d "%BODY%" > %TEMP%\proverlap-response.json

REM Check if response has success:true
findstr /C:"\"success\":true" %TEMP%\proverlap-response.json >nul
if %ERRORLEVEL% EQU 0 (
    echo.
    echo ============================================
    echo   审查结果
    echo ============================================
    type %TEMP%\proverlap-response.json
    echo.
    echo ============================================
) else (
    echo 审查失败:
    type %TEMP%\proverlap-response.json
    del %TEMP%\proverlap-response.json
    exit /b 1
)

del %TEMP%\proverlap-response.json
endlocal
