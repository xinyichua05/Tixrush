# Starts the portable PostgreSQL (port 5433). Run after every reboot:
#   powershell -ExecutionPolicy Bypass -File scripts\db-start.ps1
$pg = "$env:USERPROFILE\tools"
& "$pg\pgsql\bin\pg_ctl.exe" -D "$pg\pgdata" -l "$pg\pgdata\server.log" -w start
