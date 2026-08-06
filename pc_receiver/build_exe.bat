@echo off
rem ============================================================
rem  Build receiver.py into a standalone exe (includes Python,
rem  no Python needed on target PC).
rem  Output : dist\receiver.exe
rem  The exe is also copied into the App assets so the phone app
rem  can push it via bluetooth automatically.
rem ============================================================
echo [build] Checking Python...
py -V || (
    echo [build] Python not found. Install Python 3.9+ with "Add to PATH" checked.
    pause
    exit /b 1
)

echo [build] Installing pyinstaller...
py -m pip install --upgrade pyinstaller

echo [build] Installing runtime deps (vgamepad / PyBluezWin10)...
py -m pip install -r requirements.txt

echo [build] Building receiver.exe ...
py -m PyInstaller --onefile --name receiver --clean --collect-all vgamepad receiver.py

if not exist dist\receiver.exe (
    echo [build] BUILD FAILED. See errors above.
    pause
    exit /b 1
)

echo [build] Copying installer into App assets...
set ASSETS_DIR=..\app\src\main\assets\installer
if not exist "%ASSETS_DIR%" mkdir "%ASSETS_DIR%"
copy /y dist\receiver.exe "%ASSETS_DIR%\receiver.exe" >nul
if errorlevel 1 (
    echo [build] Copy to assets FAILED. Copy dist\receiver.exe to app\src\main\assets\installer\ manually.
) else (
    echo [build] Done. exe is now bundled into the App.
)

echo.
echo [build] USAGE:
echo   1. Rebuild the APK - the installer is now bundled.
echo   2. Phone App - WiFi bridge card - turn on the switch.
echo   3. If the PC is offline, tap "Push installer to PC" - bluetooth send.
echo   4. On PC: run receiver.exe --install (one UAC prompt), then it auto-starts.
pause
