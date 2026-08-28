@echo off
title Blinkit Hot Wheels watcher
cd /d "%~dp0"
echo Starting watcher. Close this window to stop.
echo.
:loop
python blinkit_watch.py --loop
echo.
echo Watcher exited. Restarting in 60 seconds... (Ctrl-C to quit)
timeout /t 60 /nobreak >nul
goto loop
