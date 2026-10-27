@echo off
cd /d "%~dp0"
python -c "import flask, websocket" >nul 2>&1
if errorlevel 1 (
  echo Instalando dependencias del control...
  python -m pip install -r requirements.txt
  if errorlevel 1 (
    pause
    exit /b 1
  )
)
start "" "http://localhost:8765"
python app.py
pause
