@echo off
REM Builds a standalone PhoneKeyboard.exe (no Python needed on the target PC)
cd /d "%~dp0"
python -m pip install --upgrade pyinstaller qrcode
python -m PyInstaller --onefile --name PhoneKeyboard --console phone_keyboard.py
echo.
echo Done: dist\PhoneKeyboard.exe
pause
