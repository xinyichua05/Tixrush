@echo off
rem Starts the TixRush backend. Works even when PowerShell scripts are blocked on this laptop.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-backend.ps1"
