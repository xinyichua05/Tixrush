// k6 load test: a flash-sale rush against one "Buy" strategy.
//
//   k6 run -e STRATEGY=naive loadtest/buy.js
//   k6 run -e STRATEGY=pessimistic -e VUS=200 -e ATTEMPTS=3000 -e TICKETS=500 loadtest/buy.js
//
// setup()    runs once:   admin login, create a fresh event, register buyer accounts
// default()  runs ATTEMPTS times, spread over VUS virtual users hammering at once
// teardown() runs once:   asks the server how many tickets were really sold / oversold

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const STRATEGY = __ENV.STRATEGY || 'naive';
const TICKETS = parseInt(__ENV.TICKETS || '500');
// 200, not more: on a Windows laptop, ~500 connections opened in the same instant overflow the
// OS accept queue ("connection refused") before requests even reach the app.
const VUS = parseInt(__ENV.VUS || '200');
const ATTEMPTS = parseInt(__ENV.ATTEMPTS || '5000');
const BUYER_ACCOUNTS = parseInt(__ENV.BUYERS || '50');

// Send an Idempotency-Key with every purchase (adds 2 Redis calls per request). Off by default
// so all versions are compared on equal terms.
const IDEMPOTENCY = __ENV.IDEMPOTENCY === '1';

// Custom counters, shown in the summary at the end.
const bought = new Counter('result_got_ticket_201_202'); // 202 = queued (v4 queue)
const soldOut = new Counter('result_sold_out_409');
const tooBusy = new Counter('result_too_busy_503');
const otherErrors = new Counter('result_other_errors');

export const options = {
    scenarios: {
        rush: {
            executor: 'shared-iterations', // ATTEMPTS requests in total, shared by all VUs, as fast as possible
            vus: VUS,
            iterations: ATTEMPTS,
            maxDuration: '3m',
        },
    },
    setupTimeout: '2m',
    summaryTrendStats: ['avg', 'med', 'p(95)', 'p(99)', 'max'],
};

// 409 (sold out) is an expected answer, not a failure.
http.setResponseCallback(http.expectedStatuses(201, 202, 409));

const json = { headers: { 'Content-Type': 'application/json' } };
const auth = (token) => ({ headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } });

export function setup() {
    const adminRes = http.post(`${BASE}/api/auth/login`,
        JSON.stringify({ email: 'admin@tixrush.local', password: 'admin12345' }), json);
    check(adminRes, { 'admin login 200': (r) => r.status === 200 });
    const adminToken = adminRes.json('token');

    const now = Date.now();
    const eventRes = http.post(`${BASE}/api/events`, JSON.stringify({
        name: `k6 rush (${STRATEGY}) ${new Date(now).toISOString()}`,
        venue: 'Load Test Arena',
        startsAt: new Date(now + 30 * 86400000).toISOString(),
        saleStartsAt: new Date(now + 86400000).toISOString(),
        totalTickets: TICKETS,
    }), auth(adminToken));
    check(eventRes, { 'event created 201': (r) => r.status === 201 });
    const eventId = eventRes.json('id');
    // Zero the server's optimistic-retry counter so the stats only count this run.
    http.post(`${BASE}/api/admin/events/${eventId}/reset`, null, auth(adminToken));

    // Register buyers. (BCrypt is slow on purpose, so we make a pool of accounts and share them.)
    const run = Math.random().toString(36).slice(2, 8);
    const tokens = [];
    for (let i = 0; i < BUYER_ACCOUNTS; i++) {
        const r = http.post(`${BASE}/api/auth/register`,
            JSON.stringify({ email: `buyer${i}-${run}@k6.test`, password: 'supersecret' }), json);
        tokens.push(r.json('token'));
    }

    console.log(`Event ${eventId}: ${TICKETS} tickets, ${ATTEMPTS} attempts from ${VUS} VUs, strategy=${STRATEGY}`);
    return { eventId, adminToken, tokens };
}

export default function (data) {
    const token = data.tokens[(__VU + __ITER) % data.tokens.length];
    const headers = { Authorization: `Bearer ${token}` };
    if (IDEMPOTENCY) headers['Idempotency-Key'] = `${__VU}-${__ITER}-${Math.random().toString(36).slice(2)}`;
    const res = http.post(`${BASE}/api/events/${data.eventId}/purchases?strategy=${STRATEGY}`, null,
        { headers, tags: { name: 'purchase' } });

    if (res.status === 201 || res.status === 202) bought.add(1);
    else if (res.status === 409) soldOut.add(1);
    else if (res.status === 503) tooBusy.add(1);
    else otherErrors.add(1);
}

export function teardown(data) {
    const statsUrl = `${BASE}/api/admin/events/${data.eventId}/stats`;
    let stats = http.get(statsUrl, auth(data.adminToken)).json();
    // Queue version: buyers are answered before the orders are written. Wait for the workers.
    const start = Date.now();
    let waitedMs = 0;
    while (STRATEGY === 'queue' && Date.now() - start < 60000
           && !(stats.queueBacklog === 0 && stats.ordersCount === stats.totalTickets - stats.redisStock)) {
        sleep(0.25);
        stats = http.get(statsUrl, auth(data.adminToken)).json();
        waitedMs = Date.now() - start;
    }
    console.log(`\n===== RESULT (${STRATEGY}) =====\n` +
        `tickets total     : ${stats.totalTickets}\n` +
        `left in stock (DB): ${stats.availableTickets}\n` +
        `left in Redis     : ${stats.redisStock}\n` +
        `orders created    : ${stats.ordersCount}\n` +
        `OVERSOLD          : ${stats.oversold}\n` +
        `optimistic retries: ${stats.optimisticRetries}\n` +
        (STRATEGY === 'queue' ? `workers caught up : ${waitedMs} ms after the last buyer was answered\n` : ''));

    // Clean up: delete this run's event (and its orders), so benchmark events don't pile up on the
    // website. Run with -e KEEP_EVENT=1 to keep it for inspection.
    if (__ENV.KEEP_EVENT !== '1') {
        http.del(`${BASE}/api/events/${data.eventId}`, null, auth(data.adminToken));
    }
}
