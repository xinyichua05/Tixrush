# TixRush

[![CI](https://github.com/xinyichua05/Tixrush/actions/workflows/ci.yml/badge.svg)](https://github.com/xinyichua05/Tixrush/actions/workflows/ci.yml)

**Live demo: https://tixrush-lx8q.onrender.com**: it runs on a free server, so the first visit can take about a minute to wake it up. Pay with the Stripe test card `4242 4242 4242 4242`.

**A concert-ticket flash-sale system: thousands of fans, a few hundred seats, one second.**

TixRush is a full-stack ticket shop built to survive the moment a big concert goes on sale. It never oversells. A double-click never creates two orders. Unpaid tickets go back on sale after 10 minutes. Payment runs through Stripe (test mode).

![TixRush home page](docs/screenshots/home.jpg)

| Pick your exact seats | Pay with Stripe before the timer runs out |
|---|---|
| ![Seat map](docs/screenshots/seat-map.jpg) | ![Checkout](docs/screenshots/checkout.jpg) |

**Practice rush:** race 2,000 virtual fans for 100 tickets, through a waiting room, to see how a real ticket war feels.

![Practice rush](docs/screenshots/practice-rush.jpg)

## Features

**For fans**
- **Browse concerts** with live "tickets left" counts.
- **Choose a zone** (VIP, CAT 1, Rock Zone…). In seated zones, **pick exact seats on the map** or let TixRush choose the **best seats side by side**.
- **Get tickets held for 10 minutes** while you pay, with a countdown. If you don't pay in time, or you cancel, they go back on sale automatically.
- **Pay through Stripe Checkout** in test mode: card `4242 4242 4242 4242`, no real money.
- **My tickets** shows paid, pending, expired and cancelled orders.

**Practice rush**
- **Three shows:** mega star, popular and indie, each with its own zones and crowd size.
- **Two ways to line up:**
  - **Random lobby:** like Ticketmaster or Queue-it, everyone in the lobby gets a random place.
  - **Fastest click:** a "ticket war", where your place depends on how fast you click.
- **Live numbers** for fans in line, served, holding a ticket, paid and missed out. Bots who don't pay in time lose their tickets back to the line.

**For admins**
- **Create, edit and delete events**, with a poster image and ticket types.
- **Venues** with zones and seating plans (rows × seats), reused across events.
- **The Rush Visualizer** (http://localhost:8080/visualizer/, with `DEV_TOOLS=true`): fire thousands of simulated buyers at each of the 5 purchase versions and watch for overselling live.

## How a purchase survives the rush

```
Buy ─► rate limit (Redis) ─► idempotency key ─► reserve stock/seats atomically (Redis Lua)
    ─► 202 "QUEUED" + reservationId ─► RabbitMQ ─► worker writes the order to Postgres
    ─► PENDING_PAYMENT, 10-minute hold (RabbitMQ TTL ─► "expired" queue)
    ─► Stripe Checkout ─► PAID        or        hold runs out / cancel ─► tickets released
```

- **Redis is the gatekeeper.** One Lua script checks and takes the stock in a single step, so two buyers can never get the same last seat.
- **Postgres writes go through a queue.** However big the rush, only 10–20 background workers touch the database.
- **Stripe payments are checked three ways:**
  1. when the buyer returns from Stripe
  2. by a background check every 30 s
  3. by a signed webhook

  Every check uses the same idempotent sync. A payment that arrives after the hold expired is **refunded automatically**.

The purchase step is built **five ways** and benchmarked side by side:

| Version | Req/s | p95 | Oversold |
|---|---|---|---|
| v1 naive | ~305 | ~760 ms | **~4,760** ❌ |
| v2 pessimistic lock | ~460 | ~640 ms | 0 ✅ |
| v3 optimistic lock | ~390 | ~1.5 s | 0 ✅ (~3,900 retries) |
| v4a Redis atomic counter* | ~557 | ~290 ms | 0 ✅ (but DB stock not updated) |
| v4 Redis + RabbitMQ queue** | ~900–1,000 (rush only) | ~500–560 ms | 0 ✅ + DB in step, payment hold, idempotency |

The test: 500 tickets, 5,000 attempts, 200 concurrent buyers, using k6.
- \* Redis runs on Upstash in Singapore, about 20–30 ms per round trip.
- \*\* RabbitMQ runs on CloudAMQP in Tokyo, about 95 ms per round trip. The queue trades a little latency for:
  - a capped database load
  - stock that stays in step with Redis
  - payment holds
  - safe double-clicks

Full details are in [docs/benchmarks.md](docs/benchmarks.md).

## Tech stack

| Part | Built with |
|---|---|
| Backend | Java 21, Spring Boot 4, Spring Security + JWT, Spring Data JPA, Flyway |
| Data | PostgreSQL 18, Redis (Upstash), RabbitMQ (CloudAMQP) |
| Payments | Stripe Checkout (test mode) via `stripe-java` |
| Frontend | React 19, TypeScript, Vite, React Router, TanStack Query |
| Testing | JUnit + Spring Boot Test + Testcontainers (80+ tests), Vitest + React Testing Library (90+ tests), k6 load tests |
| Delivery | Docker (one image: website + API), Docker Compose, GitHub Actions CI, Render, Supabase Storage |

## Run it with Docker (any computer)

```bash
docker compose up --build
```

**Open http://localhost:8080.** One command starts the whole system:
- the app: the React website and the API, from one image
- PostgreSQL, Redis and RabbitMQ

The admin login is `admin@tixrush.local` / `admin12345`.

**For real test payments,** put `STRIPE_SECRET_KEY=sk_test_...` in a `.env` file next to `docker-compose.yml`.

**To stop it,** run `docker compose down` (add `-v` to also delete the database).

## How it's tested and shipped

- **Every push runs [CI](.github/workflows/ci.yml) on GitHub Actions:**
  1. **Backend tests** run against throwaway Postgres, Redis and RabbitMQ containers (Testcontainers), so every run starts clean and shares nothing.
  2. **Frontend** lint, tests and a production build.
  3. **A smoke test** builds the Docker image, starts everything with Compose, and checks the site, the API, a deep link and that developer tools are hidden.
- **The live site:**
  - **Deploy:** Render runs the image and deploys only after CI is green ([render.yaml](render.yaml)).
  - **Database:** Neon (Postgres). Redis and RabbitMQ use their own production instances.
  - **Pictures:** event posters and seating plans go to **Supabase Storage** (S3-compatible) and load from its CDN. Locally they stay in Postgres.
  - **Secrets** are set only in the host's dashboard. The app refuses to start online with the development JWT secret or admin password.

## Run it locally on Windows (without Docker)

### What you need
- **Java 21, Node.js 20+ and PostgreSQL.** The start scripts expect portable copies in `%USERPROFILE%\tools`:
  - `jdk-21`
  - `pgsql`, plus a data folder `pgdata`, running on port **5433**

  If yours live elsewhere, edit the paths at the top of the scripts.
- **A Postgres database** `tixrush`, owned by user `tixrush` with password `tixrush` (dev defaults). Flyway creates the tables.
- **Redis and RabbitMQ.** Free cloud plans work: [Upstash](https://upstash.com) and [CloudAMQP](https://www.cloudamqp.com).
- **Optional:** a free [Stripe](https://stripe.com) account, for real test-mode payments.

### Set your secrets once
Set them as Windows **user** environment variables so they never land in git:

```powershell
[Environment]::SetEnvironmentVariable('REDIS_URL',         'rediss://default:<password>@<host>:6379', 'User')
[Environment]::SetEnvironmentVariable('RABBITMQ_URL',      'amqps://<user>:<password>@<host>/<vhost>', 'User')
[Environment]::SetEnvironmentVariable('STRIPE_SECRET_KEY', 'sk_test_...', 'User')   # optional
```

### Start everything

```powershell
scripts\start-all.cmd
```

The script does three things:
1. starts Postgres
2. opens the **backend** (http://localhost:8080) in its own window
3. opens the **website** (http://localhost:5173) in its own window

The first time only, run `npm install` in `frontend` before starting.

| Script | What it does |
|---|---|
| `scripts\db-start.cmd` | Starts Postgres (run after every reboot) |
| `scripts\start-backend.cmd` | Loads your secrets and runs the Spring Boot API |
| `scripts\start-frontend.cmd` | Runs the Vite dev server |
| `scripts\seed-demo-events.ps1` | Adds 7 sample concerts with ticket types (made-up artists, real Malaysian venues) |

**Dev admin login:** `admin@tixrush.local` / `admin12345`.

**Without `STRIPE_SECRET_KEY`,** checkout uses an instant test "Pay now" button instead of Stripe.

**On a company network that inspects HTTPS,** the backend script tells Java to trust the Windows certificate store (`-Djavax.net.ssl.trustStoreType=Windows-ROOT`) so Stripe calls work.

### Tests

```powershell
cd backend;  .\mvnw.cmd test             # uses Docker containers if Docker is running; otherwise your
                                         # REDIS_URL/RABBITMQ_URL (then stop the dev backend first:
                                         # it would share the RabbitMQ queues with the tests)
cd frontend; npm test; npm run lint
```

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5433/tixrush`, `tixrush` | Postgres |
| `REDIS_URL` | `redis://localhost:6379` | Stock counters, rate limits, waiting room, cache |
| `RABBITMQ_URL` | `amqp://guest:guest@localhost:5672` | Order queue and payment-hold timer |
| `STRIPE_SECRET_KEY` | *(empty: Stripe off)* | Test key `sk_test_…`; live keys are refused |
| `STRIPE_WEBHOOK_SECRET` | *(empty: webhook off)* | Set when deployed, so Stripe can call `/api/payments/stripe/webhook` |
| `FRONTEND_URL` | `http://localhost:5173` | Where Stripe sends the buyer back |
| `JWT_SECRET`, `ADMIN_EMAIL`, `ADMIN_PASSWORD` | dev values | **Change these before deploying** |
| `PAYMENT_HOLD` | `10m` | How long an unpaid order holds its tickets (try `1m` to watch them come back) |
| `STORAGE_BUCKET`, `STORAGE_ENDPOINT`, `STORAGE_REGION`, `STORAGE_ACCESS_KEY`, `STORAGE_SECRET_KEY`, `STORAGE_PUBLIC_URL` | *(empty: pictures in Postgres)* | S3-compatible object storage for pictures (Supabase Storage online) |
| `DB_POOL_SIZE` | `20` | Max database connections (8 on the free server) |
| `PORT` | `8080` | Set by the host online; also switches on the "no development secrets" check |
| `DEV_TOOLS` | `false` | `true` shows the Rush Visualizer and the benchmark endpoints |

## API overview

| Endpoint | Access |
|---|---|
| `POST /api/auth/register`, `POST /api/auth/login` | Public |
| `GET /api/events`, `GET /api/events/{id}`, `GET /api/events/{id}/ticket-types/{typeId}/seats` | Public |
| `POST /api/events/{id}/purchases?strategy=queue` (optional `Idempotency-Key` header) | Logged in, max 5/min |
| `GET /api/orders/mine`, `GET /api/orders/{rid}`, `POST /api/orders/{rid}/cancel` | Logged in, own orders only |
| `POST /api/orders/{rid}/checkout`, `POST /api/orders/{rid}/payment/check` | Logged in, own orders only (Stripe) |
| `POST /api/payments/stripe/webhook` | Stripe (signature-checked) |
| `GET/POST /api/rush-demo…` | Logged in (practice rush) |
| `POST/PUT/DELETE /api/events…`, `/api/venues…`, `/api/admin/…` | Admin |

You can try the raw API with [backend/requests.http](backend/requests.http).

## Project layout

```
backend/    Spring Boot API: event, order, seat, venue, payment, waitingroom, demo (practice rush)
frontend/   React website
loadtest/   k6 scripts for the benchmarks
scripts/    start/stop helpers and demo data
docs/       benchmarks, learning notes, screenshots
```
