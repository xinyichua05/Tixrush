@echo off
rem Starts everything for TixRush: database, then backend and website in their own windows.
rem Close a window (or press Ctrl+C in it) to stop that part.
call "%~dp0db-start.cmd"
start "TixRush backend" "%~dp0start-backend.cmd"
start "TixRush website" "%~dp0start-frontend.cmd"
echo.
echo Backend and website are starting in two new windows.
echo Open http://localhost:5173 once the backend window says "Started TixrushApplication".
