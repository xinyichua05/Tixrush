# Creates a set of sample concerts through the normal admin API, so the website looks like a real
# ticket shop. Safe to run again: events whose name already exists are skipped.
#
#   powershell -ExecutionPolicy Bypass -File scripts\seed-demo-events.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\seed-demo-events.ps1 -BaseUrl https://my-deployed-api.example.com
#
# The artists are made up. The venues are real places in Malaysia.
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$AdminEmail = 'admin@tixrush.local',
    [string]$AdminPassword = 'admin12345'
)
$ErrorActionPreference = 'Stop'

# name, venue, show starts (Malaysia time, +08:00), sale opens, ticket types.
# Ticket types are "name:price in RM:tickets", separated by "|". Every event has prices, so real
# orders pay with Stripe (when STRIPE_SECRET_KEY is set) instead of the instant test button.
$concerts = @(
    @('Neon Monsoon: Electric Rain Tour', 'Axiata Arena, Kuala Lumpur',      '2027-03-13T20:00:00+08:00', '2027-01-30T10:00:00+08:00', 'VIP:988:400|CAT 1:688:1600|CAT 2:388:2000|Rock Zone:488:2000'),
    @('Aurora Fields Festival 2027',      'Sepang International Circuit',    '2027-05-22T16:00:00+08:00', '2027-03-01T10:00:00+08:00', 'VIP Weekend:1288:2000|3-Day Pass:588:6000|Day Pass:258:12000'),
    @('The Paper Lanterns: Unplugged',    'Zepp Kuala Lumpur',               '2027-02-06T20:30:00+08:00', '2027-01-09T12:00:00+08:00', 'Balcony:328:200|Standing Free:258:1000'),
    @('Lunar Tides: Asia Tour',           'SPICE Arena, Penang',             '2027-04-17T20:00:00+08:00', '2027-03-06T10:00:00+08:00', 'VIP:788:300|CAT 1:488:1200|CAT 2:288:2000'),
    @('Kopi & Keys: A Piano Night',       'Dewan Filharmonik Petronas',      '2027-01-23T19:30:00+08:00', '2026-12-19T10:00:00+08:00', 'Stalls:238:450|Grand Tier:168:250|Gallery:98:150'),
    @('Midnight Satay Club (Jazz)',       'No Black Tie, Kuala Lumpur',      '2027-01-15T21:30:00+08:00', '2026-12-26T12:00:00+08:00', 'Table Seat:158:40|Bar Standing:88:80'),
    @('Echo Valley: Secret Show',         'The Bee, Publika',                '2027-02-26T21:00:00+08:00', '2027-01-22T12:00:00+08:00', 'General Admission:120:30')
)

$login = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/auth/login" -ContentType 'application/json' `
    -Body (@{ email = $AdminEmail; password = $AdminPassword } | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($login.token)" }

$existing = @(Invoke-RestMethod -Uri "$BaseUrl/api/events") | ForEach-Object { $_.name }

foreach ($c in $concerts) {
    if ($existing -contains $c[0]) {
        Write-Host "skipped (already exists): $($c[0])"
        continue
    }
    $body = @{
        name         = $c[0]
        venue        = $c[1]
        startsAt     = ([DateTimeOffset]::Parse($c[2])).UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ssZ')
        saleStartsAt = ([DateTimeOffset]::Parse($c[3])).UtcDateTime.ToString('yyyy-MM-ddTHH:mm:ssZ')
        ticketTypes  = @($c[4] -split '\|' | ForEach-Object {
            $name, $price, $tickets = $_ -split ':'
            @{ name = $name; price = [decimal]$price; tickets = [int]$tickets }
        })
    } | ConvertTo-Json -Depth 4
    # The body is sent as UTF-8 so names like "Kopi & Keys" arrive exactly as written.
    $created = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/events" -Headers $headers `
        -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
    Write-Host "created #$($created.id): $($created.name) ($($created.totalTickets) tickets)"
}
