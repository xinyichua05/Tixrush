# TixRush

A concert-ticket flash-sale system: 100,000 buyers, 5,000 tickets, one second.

Rules it must follow:
1. Never oversell
2. Survive the rush
3. A double-click never creates two orders
4. Unpaid tickets go back to the pool after 10 minutes

The purchase step is built four ways (naive → pessimistic lock → optimistic lock → Redis + queue),
each benchmarked. Results go in [docs/benchmarks.md](docs/benchmarks.md) (week 3+).

## Run locally

```powershell
powershell -ExecutionPolicy Bypass -File scripts\db-start.ps1   # Postgres on :5433
cd backend
.\mvnw.cmd spring-boot:run                                      # API on :8080
```

**Sample concerts:** with the backend running, `powershell -ExecutionPolicy Bypass -File scripts\seed-demo-events.ps1` adds 7 demo concerts (made-up artists at real Malaysian venues). It's safe to run again.

### The website (React + TypeScript)

```powershell
cd frontend
npm install        # first time only
npm run dev        # website on http://localhost:5173 (the backend must be running too)
```

**http://localhost:5173** is the fan-facing site:
- browse concerts and see seats left (live)
- sign up / log in
- **Buy**: a double-click is safe, and you get a countdown if you're rate-limited
- checkout with a **payment countdown** and Pay button
- My tickets
- an Admin page for creating, editing and deleting events

The Vite dev server forwards `/api` to `localhost:8080`, so no CORS setup is needed.
`npm test` runs the frontend tests (Vitest + React Testing Library), and `npm run build` type-checks and builds `dist/`.

### The Rush Visualizer (developer tool)

Open **http://localhost:8080/** for the **Rush Visualizer**:
- pick a Buy version
- fire thousands of simulated buyers (the rush runs inside the server)
- watch the seats fill up live, plus red "phantom" seats when a version oversells
- compare runs in a history table
- buy a ticket yourself as a fan, including hitting the 429 rate limit

Try the raw API with [backend/requests.http](backend/requests.http).

**Dev admin login:** `admin@tixrush.local` / `admin12345`. Override it with the `ADMIN_EMAIL` and `ADMIN_PASSWORD` env vars, and set `JWT_SECRET` in any real deployment.

| Endpoint | Access |
|---|---|
| `POST /api/auth/register`, `POST /api/auth/login` | Public |
| `GET /api/events`, `GET /api/events/{id}` | Public |
| `POST/PUT/DELETE /api/events...` | ADMIN token |
| `POST /api/events/{id}/purchases?strategy=naive\|pessimistic\|optimistic\|redis\|queue` | Any logged-in user (max 5/min) |
| `/api/admin/events/{id}/stats`, `/reset`, `/api/admin/redis/ping`, `/actuator/metrics` | ADMIN token |
| `POST /api/admin/events/{id}/rush?strategy=&buyers=&concurrency=`, `GET /api/admin/rush` | ADMIN token (used by the visualizer) |

**Redis:** set the `REDIS_URL` user environment variable (e.g. an Upstash `rediss://...` URL). Without it, the app expects Redis on `localhost:6379`.

**RabbitMQ:** set `RABBITMQ_URL` (e.g. a CloudAMQP `amqps://...` URL). Without it, the app expects RabbitMQ on `localhost:5672`. The payment hold defaults to 10 minutes; set `PAYMENT_HOLD=1m` to watch unpaid seats come back in the visualizer.

| Your orders (v4 queue) | Access |
|---|---|
| `GET /api/orders/mine`, `GET /api/orders/{reservationId}`, `POST /api/orders/{reservationId}/pay` | Logged-in user (own orders only) |
| `Idempotency-Key: <random>` header on purchases | Optional: a repeat with the same key returns the same answer |

## Benchmarks so far

500 tickets, 5,000 attempts, 200 concurrent buyers (details in [docs/benchmarks.md](docs/benchmarks.md)):

| Version | Req/s | p95 | Oversold |
|---|---|---|---|
| v1 naive | ~305 | ~760 ms | **~4,760** ❌ |
| v2 pessimistic lock | ~460 | ~640 ms | 0 ✅ |
| v3 optimistic lock | ~390 | ~1.5 s | 0 ✅ (~3,900 retries) |
| v4a Redis atomic counter* | ~557 | ~290 ms | 0 ✅ (but DB stock not updated) |
| v4 Redis + RabbitMQ queue** | ~900–1,000 (rush only) | ~500–560 ms | 0 ✅ + DB in step, payment hold, idempotency |

\* Redis is on Upstash (Singapore), about 20–30 ms per round trip.
\*\* RabbitMQ is on CloudAMQP (Tokyo), about 95 ms per round trip, and each purchase waits for the broker's confirm, so here the buyer's answer is a bit slower than v4a. What the queue buys you: Postgres writes are capped at 10–20 background workers however big the rush, the DB stock stays in step with Redis, unpaid tickets come back, and double-clicks are safe. See [docs/benchmarks.md](docs/benchmarks.md).

## Stack
Java 21 · Spring Boot 4 · PostgreSQL 18 · Flyway · Spring Security + JWT · Redis (Upstash) · RabbitMQ (CloudAMQP) · k6 · React 19 + TypeScript + Vite · React Router · TanStack Query · Vitest
