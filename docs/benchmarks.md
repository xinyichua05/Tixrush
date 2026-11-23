# "Buy" benchmarks

**Setup:** one event with **500 tickets**, and **5,000 purchase attempts** fired by **200 concurrent virtual users** (k6, `shared-iterations`).
- Everything runs on one Windows laptop: the Spring Boot app (`bench` profile, SQL logging off), PostgreSQL 18 and k6 itself.
- The DB connection pool has 20 connections, and Tomcat runs 200 worker threads.
- Each version was run twice after a warm-up run; the two rounds are shown as `round 1 / round 2`.

> Absolute numbers depend on the machine. Compare the versions **with each other**, not with other benchmarks.

## Results (2026-11-13)

| Version | Requests/s | p50 latency | p95 latency | p99 latency | Tickets sold (201) | Sold out (409) | Too busy (503) | **Oversold** | Retries |
|---|---|---|---|---|---|---|---|---|---|
| v1 naive | 296 / 316 | 449 / 433 ms | 872 / 646 ms | 1.26 / 0.96 s | **5,000** | 0 | 0 | **4,755 / 4,768** ❌ | — |
| v2 pessimistic lock | 470 / 450 | 193 / 194 ms | 619 / 665 ms | 0.91 / 0.97 s | 500 | 4,500 | 0 | **0** ✅ | — |
| v3 optimistic lock | 394 / 389 | 144 / 148 ms | 1.48 / 1.51 s | 2.36 / 2.29 s | 500 | ~4,340 | ~158 | **0** ✅ | ~3,900 |
| v4 part 1: Redis counter (no queue yet) | 558 / 557 | 156 / 172 ms | 340 / 245 ms | 0.47 / 0.67 s | 500 | 4,500 | 0 | **0** ✅ | — |
| v4 complete: Redis + queue (see below) | ~909 / ~1,000 † | 182 / 173 ms | 558 / 502 ms | 0.69 / 0.79 s | 500 (202) | 4,500 | 0 | **0** ✅ | — |

† Measured over the rush only (5,000 ÷ rush time). The rows above use k6's `http_reqs` rate over the whole test, which for the queue version would also include the teardown time spent waiting for the workers to finish writing.

### v4 part 1 run (2026-11-13), with a same-session reference

| Run | Requests/s | p50 | p95 | p99 | Oversold |
|---|---|---|---|---|---|
| redis round 1 | 558 | 156 ms | 340 ms | 473 ms | 0 |
| pessimistic (reference, same session) | 477 | 201 ms | 554 ms | 727 ms | 0 |
| redis round 2 | 557 | 172 ms | 245 ms | 672 ms | 0 |

**Important caveat: Redis is NOT local.** It runs on Upstash (Singapore). Every Redis call crosses the internet: about **18 ms at best, 32 ms on average** (measured with `GET /api/admin/redis/ping`). Postgres is on the same laptop (<1 ms). So v4 is winning *despite* a ~20–30 ms handicap per request. With Redis in the same data centre as the app, the gap would be much larger.

### v4 complete run (2026-11-13): Redis + RabbitMQ, with a same-session reference

RabbitMQ is on **CloudAMQP (free plan), in Tokyo: ~95 ms TCP round trip** from the laptop. Every queued purchase waits for the broker's *publisher confirm*, so it pays at least one Tokyo round trip.

| Run | Rush time | Req/s (rush) | p50 | p95 | p99 | Oversold | DB stock after |
|---|---|---|---|---|---|---|---|
| redis (v4a, reference) | 3.8 s | ~1,316 | 133 ms | 357 ms | 542 ms | 0 | 500 ❌ (never updated) |
| queue round 1 | 5.5 s | ~909 | 182 ms | 558 ms | 689 ms | 0 | 0 ✅ |
| redis (v4a, reference) | 5.0 s | ~1,000 | 183 ms | 409 ms | 668 ms | 0 | 500 ❌ |
| queue round 2 | 5.0 s | ~1,000 | 173 ms | 502 ms | 793 ms | 0 | 0 ✅ |

**Background workers** (server-side visualizer rush, 2,000 buyers, 200 tickets): all 200 orders were in Postgres **3–5 s** after the last buyer was answered. The first version took **32 s** (see below).

**Lesson found while measuring: don't hold a DB lock across a network call.**
- The first worker did "INSERT order, stock − 1 (locks the hot events row), send the 'payment timer' message and wait ~100–200 ms for Tokyo to confirm, COMMIT".
- So every worker held the row lock during a network round trip, and the 5 workers ended up taking turns: ~6 orders/s, 32 s for 200 orders.
- Fix: send the timer message *first*, then run a tiny transaction (insert + stock − 1 + commit, a few ms). That gave 6.7 s. Raising the workers from 5–10 to 10–20 (they mostly wait on the network, not the DB) gave **3–5 s**.

## What the numbers say

**v1 naive: wrong.**
- Every one of the 5,000 buyers got a "ticket" for a 500-seat event, and the stock counter *still* said ~260 seats were left.
- Cause: buyers read the same stock value and overwrite each other's update (a *lost update*).
- It's also the slowest version, because every request did a full write instead of stopping at "sold out".

**v2 pessimistic: correct, and the fastest here.**
- `SELECT … FOR UPDATE` makes buyers queue on the row lock, one at a time.
- With a single hot row and short transactions, the queue moves fast, so nobody wastes work.
- **Limit:** each waiting buyer holds a DB connection while they wait. With 20 connections, at most 20 buyers are "in the building" at once, and the rest queue in Tomcat. Under a bigger rush, or with slower transactions, the connection pool runs out first.

**v3 optimistic: correct, but wasteful under contention.**
- ~3,900 retries for 500 successful sales, about **8 failed tries per ticket sold**.
- The median request is fast (no lock waiting), but the unlucky ones retry many times, which makes the **p95/p99 latency the worst** of the three.
- ~158 buyers gave up after 10 attempts and got 503, even though tickets were still left at that moment.
- **Lesson:** optimistic locking is great when conflicts are *rare* (e.g. two admins editing the same event). It's a bad fit when *everyone* fights over *one row*, which is exactly what a flash sale is.

**v4 part 1 (Redis counter): correct, and the fastest, even over the internet.**
- ~17% more requests/s than pessimistic in the same session, and a clearly lower p95.
- The hot `events` row is never touched during the sale: Redis hands out tickets with one atomic Lua script, and Postgres only receives `INSERT`s of separate order rows, which don't block each other.
- After sell-out, a "sold out" answer costs one Redis call and **zero** DB work (pessimistic still takes a row lock just to say "sold out").
- **What limits it now:** the 20–30 ms internet round trip per request, plus the synchronous `INSERT` (the buyer still waits for Postgres). Week 5 removes the second one by putting the order on a RabbitMQ queue and answering the buyer immediately.

**v4 complete (Redis + queue): correct and complete, but not faster for the buyer HERE.**
- It sells exactly 500, and unlike v4a, the **DB stock ends at 0**: the workers keep Postgres in step with Redis.
- It also adds the **10-minute payment hold** (unpaid tickets go back on sale, in both the DB and Redis) and **idempotency keys** (a double-click gets the same order back).
- The buyer's answer is a bit *slower* than v4a in this setup. The confirmed publish crosses ~95 ms to Tokyo, while v4a's INSERT goes to a Postgres on the same laptop (<1 ms).
- In a real deployment, the app, broker and DB sit in the same data centre (~1 ms apart). Then the publish costs about as much as the INSERT, and the queue's real benefit shows: **Postgres write load no longer grows with the rush**. At most 10–20 workers write, at a steady pace, however many buyers arrive (load levelling).
- **Lesson:** a queue isn't automatically "faster". It moves work out of the request and protects the database, and each network hop (here, the confirm) has a price.

## Reproduce
See [loadtest/README.md](../loadtest/README.md).
