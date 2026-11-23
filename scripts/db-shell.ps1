# Opens a SQL shell on the tixrush database. Try: \dt   select * from events;   \q to quit
$env:PGPASSWORD = 'tixrush'
& "$env:USERPROFILE\tools\pgsql\bin\psql.exe" -h localhost -p 5433 -U tixrush tixrush
