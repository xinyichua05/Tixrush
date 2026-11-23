@echo off
rem Starts the portable PostgreSQL (port 5433). Run once after every reboot.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0db-start.ps1"
