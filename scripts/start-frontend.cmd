@echo off
rem Starts the TixRush website on http://localhost:5173 (npm.cmd skips the blocked npm.ps1).
cd /d "%~dp0..\frontend"
npm.cmd run dev
