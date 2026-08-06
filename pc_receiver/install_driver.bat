@echo off
chcp 65001 >nul
echo ============================================
echo   ViGEmBus 驱动安装（首次使用只需运行一次）
echo ============================================
echo.

net session >nul 2>&1
if errorlevel 1 (
    echo 错误：需要管理员权限。
    echo 请右键本文件，选择"以管理员身份运行"。
    pause
    exit /b 1
)

echo 检查 / 安装 Python 依赖（vgamepad）...
python -c "import vgamepad" 2>nul
if errorlevel 1 (
    pip install vgamepad
)

echo 运行 ViGEmBus 安装器（弹出窗口请点击"Install"，仅此一次）...
python -c "import vgamepad; vgamepad.install_driver()"

echo.
echo 完成。现在可以运行：python receiver.py
pause
