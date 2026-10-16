@echo off
rem 双击启动电脑端中继。
rem 需要已安装 Node.js 22 以上，并已打开 DSH 桌面版。
rem 想改端口时，把下面的 server.mjs 后面加上 --port <端口>。
chcp 65001 >nul
cd /d "%~dp0.."
node relay\src\server.mjs %*
echo.
echo 中继已退出。按任意键关闭窗口。
pause >nul
