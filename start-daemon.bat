@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

REM ============================================
REM 云隙泡后台守护进程启动脚本（替代 .vbs）
REM 用法：直接双击运行，或由任务计划/快捷方式调用
REM ============================================

set "CURRENT_DIR=%~dp0"
set "LOG=%CURRENT_DIR%startup.log"

echo [startup] %date% %time% - starting... > "%LOG%"

REM ── Node.js 路径查找 ──
set "NODE_EXE="
set "NODE_CANDIDATES=%ProgramFiles%\nodejs\node.exe;%ProgramFiles(x86)%\nodejs\node.exe;%LOCALAPPDATA%\Programs\nodejs\node.exe;%APPDATA%\nvm\node.exe;%USERPROFILE%\scoop\apps\nodejs\current\node.exe;%USERPROFILE%\AppData\Local\fnm\node.exe"

REM 从 PATH 查找 node
for %%i in (node.exe) do set "NODE_PATHSEARCH=%%~$PATH:i"
if defined NODE_PATHSEARCH set "NODE_EXE=%NODE_PATHSEARCH%"

REM 备选：从固定路径查找
if not defined NODE_EXE (
    for %%p in (%NODE_CANDIDATES%) do (
        if exist "%%p" (
            set "NODE_EXE=%%p"
            goto :node_found
        )
    )
)

:node_found
if not defined NODE_EXE (
    echo [startup] WARNING: Node.js not found - QQ音乐 API 将不可用 >> "%LOG%"
) else (
    echo [startup] Node.js: %NODE_EXE% >> "%LOG%"
)

REM ── 1. MediaInfoDaemon (.NET 8 SMTC 守护进程) ──
if exist "%CURRENT_DIR%MediaInfoDaemon.exe" (
    start "" /B "%CURRENT_DIR%MediaInfoDaemon.exe" >nul 2>&1
    echo [startup] MediaInfoDaemon launched >> "%LOG%"
) else (
    echo [startup] WARNING: MediaInfoDaemon.exe not found >> "%LOG%"
)

REM ── 2. ncm-server (网易云 API 代理) ──
if exist "%CURRENT_DIR%ncm-server.exe" (
    start "" /B "%CURRENT_DIR%ncm-server.exe" >nul 2>&1
    echo [startup] ncm-server launched >> "%LOG%"
) else (
    echo [startup] WARNING: ncm-server.exe not found >> "%LOG%"
)

REM ── 3. qqmusic-api (QQ音乐 API 代理) ──
if defined NODE_EXE (
    if exist "%CURRENT_DIR%QQMusicapi\src\server.js" (
        cd /d "%CURRENT_DIR%QQMusicapi"
        start "" /B "%NODE_EXE%" src\server.js >nul 2>&1
        cd /d "%CURRENT_DIR%"
        echo [startup] qqmusic-api launched >> "%LOG%"
    ) else (
        echo [startup] WARNING: QQMusicapi\src\server.js not found >> "%LOG%"
    )
)

echo [startup] %date% %time% - all daemons launched >> "%LOG%"
exit /b 0
