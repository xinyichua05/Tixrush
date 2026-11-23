# Load tests (k6)

k6 is installed as a portable exe at `%USERPROFILE%\tools\k6\k6.exe` and is on your user PATH. Restart the terminal if `k6` isn't found.

## Run a benchmark

1. Start Postgres: `powershell -ExecutionPolicy Bypass -File scripts\db-start.ps1`
2. Start the app in **bench** mode (SQL logging off, bigger accept queue):
   ```powershell
   cd backend
   .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=bench"
   ```
3. In another terminal, from the project root:
   ```powershell
   k6 run -e STRATEGY=naive loadtest/buy.js
   k6 run -e STRATEGY=pessimistic loadtest/buy.js
   k6 run -e STRATEGY=optimistic loadtest/buy.js
   ```
   Do one throw-away run first. A fresh JVM is slower until its JIT compiler warms up.

The `===== RESULT =====` block at the end shows tickets sold, stock left, and **OVERSOLD**.

## Options (`-e NAME=value`)

| Name | Default | Meaning |
|---|---|---|
| `STRATEGY` | `naive` | `naive`, `pessimistic`, `optimistic`, `redis` or `queue` |
| `KEEP_EVENT` | (off) | `1` = keep the benchmark event afterwards. By default it's deleted at the end, so test events don't clutter the website. |
| `IDEMPOTENCY` | (off) | `1` = send an `Idempotency-Key` with every purchase |
| `TICKETS` | 500 | Tickets in the event |
| `ATTEMPTS` | 5000 | Total purchase requests |
| `VUS` | 200 | Virtual users firing at the same time |
| `BUYERS` | 50 | Buyer accounts created in setup (shared by the VUs) |
| `BASE_URL` | `http://localhost:8080` | Target server |

## Reading the summary

- `result_got_ticket_201_202`: got a ticket (202 = queued, for the `queue` version)
- `result_sold_out_409`: correctly told "sold out"
- `result_too_busy_503`: optimistic version gave up after 10 retries
- `result_other_errors`: anything else. If you see "connection refused" here, lower `VUS`. That's the laptop's OS refusing connections, not the app.
- `http_req_duration`: latency. **p95** means 95% of requests were faster than this.
