@echo off
chcp 65001 > nul 2>&1
setlocal enabledelayedexpansion

echo [1/4] Entering apd directory...
cd /d apd
if errorlevel 1 (
    echo Error: Failed to enter apd directory, please check if the directory exists!
    pause
    exit /b 1
)

echo [2/4] Executing cargo clean...
cargo clean
if errorlevel 1 (
    echo Error: cargo clean execution failed!
    pause
    exit /b 1
)

echo [3/4] Returning to parent directory...
cd ..
if errorlevel 1 (
    echo Error: Failed to return to parent directory!
    pause
    exit /b 1
)

echo [4/4] Executing gradlew.bat assembleRelease...
.\gradlew.bat assembleRelease
if errorlevel 1 (
    echo Error: gradlew.bat assembleDebug execution failed!
    pause
    exit /b 1
)

echo.
echo All commands executed successfully!
pause
endlocal