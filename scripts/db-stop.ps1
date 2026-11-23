# Stops the portable PostgreSQL.
$pg = "$env:USERPROFILE\tools"
& "$pg\pgsql\bin\pg_ctl.exe" -D "$pg\pgdata" -w stop
