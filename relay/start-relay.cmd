@echo off
rem DSH 中继 —— 双击启动
rem
rem 直接双击即可：脚本会自动切到自己的目录，启动中继，并在浏览器里打开控制面板
rem （面板显示局域网地址、公网地址与配对码）。
rem
rem 想开公网地址：把下面这行改成  start-relay.cmd --tunnel
rem 想换端口：      start-relay.cmd --port 19390
rem
rem 需要电脑装有 Node.js 22 或更高版本，并已打开 DSH 桌面版。

chcp 65001 >nul
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
  echo.
  echo 没有找到 Node.js。请先安装 Node.js 22 或更高版本：https://nodejs.org/
  echo 安装后重新双击本文件。
  echo.
  pause
  exit /b 1
)

echo 正在启动 DSH 中继…
echo 关闭本窗口即停止服务。
echo.

start "" "http://127.0.0.1:19390/"
node "src\server.mjs" %*

echo.
echo 中继已退出。
pause
