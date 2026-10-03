@echo off
cd /d "%~dp0"
where python >nul 2>nul || (echo Python not found. Install it from https://www.python.org/downloads/ ^(tick "Add to PATH"^) & pause & exit /b)
python phone_keyboard.py %*
pause
