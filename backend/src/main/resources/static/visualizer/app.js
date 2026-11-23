// TixRush Rush Visualizer: plain JavaScript, no build step.
// It only calls the existing REST API plus /api/admin/events/{id}/rush (start) and /api/admin/rush (progress).
'use strict';

const $ = (id) => document.getElementById(id);
const ADMIN = { email: 'admin@tixrush.local', password: 'admin12345' }; // dev-only seeded admin
const MAX_PHANTOMS_DRAWN = 1500;
const POLL_MS = 250;

// ---------------------------------------------------------------- the 4 Buy versions
const STRATEGIES = {
  naive: {
    label: 'v1 Naive', short: 'read, check, write. No lock.',
    caption: 'Every buyer reads "stock = 37", and many of them write "36" at the same time. They overwrite each other (lost update), so more orders are created than seats exist.',
    nodes: { redis: 'off', rabbit: 'off', stock: ['danger', 'read → write, no lock'], orders: ['danger', 'one INSERT per "sale"'] },
  },
  pessimistic: {
    label: 'v2 Pessimistic lock', short: 'SELECT … FOR UPDATE',
    caption: 'The first buyer locks the stock row. Everyone else waits in line until the lock is released, so it is one at a time on that row. Correct, but each waiting buyer holds a DB connection.',
    nodes: { redis: 'off', rabbit: 'off', stock: ['hot', '🔒 lock row, one at a time'], orders: ['good', 'INSERT order'] },
  },
  optimistic: {
    label: 'v3 Optimistic lock', short: '@Version check + retry',
    caption: 'Nobody waits. Everyone tries to update "where version = 7". Only one wins each round, and the losers retry (up to 10 times, then 503). Correct, but lots of wasted work when everyone fights over one row.',
    nodes: { redis: 'off', rabbit: 'off', stock: ['hot', 'version check, losers retry'], orders: ['good', 'INSERT order'] },
  },
  redis: {
    label: 'v4a Redis counter', short: 'atomic Lua DECR in Redis',
    caption: 'Redis hands out tickets with one atomic step ("if stock > 0 then stock − 1"). The busy stock row in Postgres is not touched at all, and Postgres only receives INSERTs of separate order rows, which don\'t block each other. (Gap: the DB stock number is never updated.)',
    nodes: { redis: ['good', 'atomic DECR (~20–30 ms away)'], rabbit: 'off', stock: 'off', orders: ['good', 'INSERT order (buyer waits)'] },
  },
  queue: {
    label: 'v4 Redis + queue', short: 'Redis says yes, RabbitMQ does the rest',
    caption: 'Redis hands out the ticket, a message goes into RabbitMQ, and the buyer gets "202, you\'re in" immediately, like a restaurant buzzer. Background workers then create the order and update the DB stock a moment later. Unpaid orders expire after the payment hold and the ticket goes back on sale.',
    nodes: {
      redis: ['good', 'atomic DECR'], rabbit: ['good', 'message queued, buyer gets 202'],
      stock: ['good', '−1 later, by a worker'], orders: ['good', 'INSERT later, by a worker'],
    },
  },
};
const NODES = [
  { id: 'buyers', title: 'Buyers', what: 'many clicks at once' },
  { id: 'app', title: 'Spring app', what: 'PurchaseService picks the version' },
  { id: 'redis', title: 'Redis', what: 'ticket counter in memory' },
  { id: 'rabbit', title: 'RabbitMQ', what: 'queue of "create order" messages' },
  { id: 'stock', title: 'Postgres: events row', what: 'available_tickets (one hot row)' },
  { id: 'orders', title: 'Postgres: orders', what: 'one row per sold ticket' },
];

// ---------------------------------------------------------------- state
const state = {
  adminToken: null,
  fanToken: null,
  events: [],
  eventId: null,
  strategy: 'pessimistic',
  running: false,
  seatsDrawn: 0,      // total seats in the map
  soldDrawn: 0,       // how many are green
  phantomsDrawn: 0,
  history: loadHistory(),
};

// ---------------------------------------------------------------- HTTP helpers
async function api(path, { method = 'GET', body, token, auth = 'admin' } = {}) {
  const headers = {};
  const bearer = token ?? (auth === 'admin' ? state.adminToken : auth === 'fan' ? state.fanToken : null);
  if (bearer) headers.Authorization = 'Bearer ' + bearer;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  // Admin token expired (1 h)? Log in again once and retry.
  if (res.status === 401 && auth === 'admin' && !token) {
    await adminLogin();
    headers.Authorization = 'Bearer ' + state.adminToken;
    res = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  }
  const text = await res.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  return { ok: res.ok, status: res.status, data, headers: res.headers };
}

function errorText(r) {
  if (r.data && typeof r.data === 'object') {
    if (r.data.errors) return Object.entries(r.data.errors).map(([k, v]) => `${k}: ${v}`).join(', ');
    return r.data.detail || r.data.title || JSON.stringify(r.data);
  }
  return r.data || ('HTTP ' + r.status);
}

const sleep = (ms) => new Promise((ok) => setTimeout(ok, ms));
const fmt = (n) => (n === null || n === undefined) ? '–' : Number(n).toLocaleString();
const ms = (n) => (n === null || n === undefined) ? '–' : (n >= 1000 ? (n / 1000).toFixed(2) + ' s' : Math.round(n) + ' ms');

// ---------------------------------------------------------------- admin login + events
async function adminLogin() {
  const r = await fetch('/api/auth/login', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(ADMIN),
  });
  const pill = $('adminStatus');
  if (!r.ok) {
    pill.textContent = 'Admin login failed (is the backend running?)';
    pill.className = 'pill err';
    throw new Error('admin login failed');
  }
  state.adminToken = (await r.json()).token;
  pill.textContent = 'Admin: logged in';
  pill.className = 'pill ok';
}

async function loadEvents(selectId) {
  const r = await api('/api/events', { auth: 'none' });
  if (!r.ok) return;
  state.events = r.data;
  const sel = $('eventSelect');
  sel.innerHTML = '';
  if (state.events.length === 0) {
    sel.innerHTML = '<option value="">No events yet: create one →</option>';
  }
  for (const e of state.events) {
    const opt = document.createElement('option');
    opt.value = e.id;
    opt.textContent = `#${e.id} ${e.name} (${fmt(e.totalTickets)} seats)`;
    sel.appendChild(opt);
  }
  const wanted = selectId ?? state.eventId ?? state.events.at(-1)?.id;
  if (wanted && state.events.some((e) => e.id === Number(wanted))) sel.value = wanted;
  await onEventChanged();
}

async function onEventChanged() {
  state.eventId = Number($('eventSelect').value) || null;
  const ev = currentEvent();
  drawSeatMap(ev ? ev.totalTickets : 0);
  if (ev) {
    const s = await api(`/api/admin/events/${ev.id}/stats`);
    if (s.ok) renderStats(s.data, null);
  } else {
    renderCounters(null, null);
  }
  refreshEventCard();
}

const currentEvent = () => state.events.find((e) => e.id === state.eventId);

async function createEvent() {
  const tickets = Math.max(1, Math.min(5000, Number($('newTickets').value) || 200));
  const now = Date.now();
  const day = 86_400_000;
  const r = await api('/api/events', {
    method: 'POST',
    body: {
      name: 'Rush Demo ' + new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
      venue: 'Visualizer Arena',
      startsAt: new Date(now + 30 * day).toISOString(),
      saleStartsAt: new Date(now + day).toISOString(),
      totalTickets: tickets,
    },
  });
  if (!r.ok) return alert('Could not create the event: ' + errorText(r));
  await loadEvents(r.data.id);
}

// ---------------------------------------------------------------- strategy picker + path diagram
function renderStrategyCards() {
  const wrap = $('strategyCards');
  wrap.innerHTML = '';
  for (const [key, s] of Object.entries(STRATEGIES)) {
    const card = document.createElement('label');
    card.className = 'strategy-card' + (key === state.strategy ? ' selected' : '');
    card.innerHTML = `<input type="radio" name="strategy" value="${key}"><b>${s.label}</b><span>${s.short}</span>`;
    card.addEventListener('click', () => { state.strategy = key; renderStrategyCards(); renderPath(); });
    wrap.appendChild(card);
  }
  // Fan view dropdown: same 4 versions.
  const fanSel = $('fanStrategy');
  if (!fanSel.options.length) {
    for (const [key, s] of Object.entries(STRATEGIES)) fanSel.add(new Option(s.label, key));
    fanSel.value = 'queue';
  }
}

function renderPath() {
  const s = STRATEGIES[state.strategy];
  $('pathTitle').textContent = s.label;
  $('pathCaption').textContent = s.caption;
  const wrap = $('pathDiagram');
  wrap.innerHTML = '';
  NODES.forEach((n, i) => {
    if (i > 0) {
      const a = document.createElement('div');
      a.className = 'arrow';
      a.textContent = '→';
      wrap.appendChild(a);
    }
    const spec = s.nodes[n.id];
    const [cls, note] = spec === 'off' ? ['off', 'not used'] : Array.isArray(spec) ? spec : ['', ''];
    const el = document.createElement('div');
    el.className = 'node ' + cls;
    el.innerHTML = `<div class="title">${n.title}</div><div class="what">${n.what}</div>` +
      (note ? `<div class="note">${note}</div>` : '');
    wrap.appendChild(el);
  });
}

// ---------------------------------------------------------------- seat map
function drawSeatMap(total) {
  const map = $('seatMap');
  map.innerHTML = '';
  const frag = document.createDocumentFragment();
  for (let i = 0; i < total; i++) {
    const d = document.createElement('div');
    d.className = 'seat';
    frag.appendChild(d);
  }
  map.appendChild(frag);
  state.seatsDrawn = total;
  state.soldDrawn = 0;
  $('phantomMap').innerHTML = '';
  state.phantomsDrawn = 0;
  $('phantomWrap').hidden = true;
  $('seatsLabel').textContent = total ? `(${fmt(total)} real seats)` : '';
}

/**
 * Green = confirmed/paid, yellow = held, waiting for payment (v4 queue), grey = free.
 * Red squares (separate box) = orders beyond the real seats.
 * Expired orders don't count, so their seats turn grey again.
 */
function updateSeats(stats) {
  const orders = stats.ordersCount || 0;
  const pending = stats.pendingPayment || 0;
  const seats = $('seatMap').children;
  const taken = Math.min(orders, state.seatsDrawn);
  const green = Math.max(0, taken - pending);
  for (let i = 0; i < seats.length; i++) {
    const cls = i < green ? 'seat sold' : i < taken ? 'seat pending' : 'seat';
    if (seats[i].className !== cls) seats[i].className = cls;
  }
  state.soldDrawn = taken;

  const phantoms = Math.max(0, orders - state.seatsDrawn);
  $('phantomWrap').hidden = phantoms === 0;
  $('phantomCount').textContent = fmt(phantoms);
  const want = Math.min(phantoms, MAX_PHANTOMS_DRAWN);
  const pm = $('phantomMap');
  if (want > state.phantomsDrawn) {
    const frag = document.createDocumentFragment();
    for (let i = state.phantomsDrawn; i < want; i++) {
      const d = document.createElement('div');
      d.className = 'seat phantom';
      frag.appendChild(d);
    }
    pm.appendChild(frag);
  } else {
    while (pm.children.length > want) pm.lastChild.remove();
  }
  state.phantomsDrawn = want;
  $('phantomMore').textContent = phantoms > want ? `…and ${fmt(phantoms - want)} more not drawn` : '';
}

// ---------------------------------------------------------------- counters
function counter(label, value, cls = '', sub = '') {
  return `<div class="counter ${cls}"><div class="label">${label}</div><div class="value">${value}</div>` +
    (sub ? `<div class="sub">${sub}</div>` : '') + '</div>';
}

function renderCounters(stats, rush) {
  const r = rush || {};
  const st = stats || {};
  const oversold = st.oversold ?? 0;
  const html = [
    counter('Requests done', rush ? `${fmt(r.done)}<span class="muted" style="font-size:13px"> / ${fmt(r.buyers)}</span>` : '–'),
    counter('Got a ticket (201)', rush ? fmt(r.sold) : '–', 'good'),
    counter('Sold out (409)', rush ? fmt(r.soldOut) : '–'),
    counter('Too busy (503)', rush ? fmt(r.tooBusy) : '–', r.tooBusy ? 'warn' : '', 'optimistic gave up'),
    counter('Orders in DB', fmt(st.ordersCount)),
    counter('Oversold', fmt(st.ordersCount === undefined ? null : oversold), oversold > 0 ? 'bad alert' : 'good',
      'orders not backed by the stock counter'),
    counter('Stock left (DB)', fmt(st.availableTickets), '',
      rush && r.strategy === 'redis' ?'redis version skips the DB row (fixed in Step 5)' : ''),
    counter('Stock left (Redis)', st.redisStock === null || st.redisStock === undefined ? '–' : fmt(st.redisStock), '',
      'only used by v4'),
    counter('Optimistic retries', fmt(st.optimisticRetries), st.optimisticRetries ? 'warn' : ''),
    counter('Waiting in RabbitMQ', st.queueBacklog === null || st.queueBacklog === undefined ? '–' : fmt(st.queueBacklog),
      st.queueBacklog ? 'warn' : '', 'orders not in the DB yet'),
    counter('Waiting for payment', fmt(st.pendingPayment), st.pendingPayment ? 'warn' : '', 'yellow seats'),
    counter('Paid', fmt(st.paid), 'good'),
    counter('Expired → back on sale', fmt(st.expired), '', 'unpaid after the hold'),
    counter('Requests / s', rush ? fmt(r.reqPerSec) : '–'),
    counter('Elapsed', rush ? ms(r.elapsedMs) : '–'),
    counter('Latency p50 / p95', rush && r.p50Ms != null ? `${ms(r.p50Ms)} / ${ms(r.p95Ms)}` : '–', '',
      rush && r.p99Ms != null ? `p99 ${ms(r.p99Ms)}` : 'shown when finished'),
  ];
  if (rush && r.errors) html.push(counter('Errors', fmt(r.errors), 'bad', r.lastError || ''));
  $('counters').innerHTML = html.join('');
}

function renderStats(stats, rush) {
  state.lastStats = stats;
  renderCounters(stats, rush);
  updateSeats(stats);
  if (rush && rush.buyers) {
    $('progressBar').style.width = Math.min(100, (rush.done / rush.buyers) * 100) + '%';
  }
}

// ---------------------------------------------------------------- the rush
async function startRush() {
  const ev = currentEvent();
  if (!ev) return alert('Pick or create an event first.');
  if (state.running) return;
  const buyers = Number($('buyers').value) || 2000;
  const concurrency = Number($('concurrency').value) || 100;
  const strategy = state.strategy;

  state.lastRush = null;
  setRunning(true, 'resetting…');
  const reset = await api(`/api/admin/events/${ev.id}/reset`, { method: 'POST' });
  if (!reset.ok) { setRunning(false); return alert('Reset failed: ' + errorText(reset)); }
  drawSeatMap(ev.totalTickets);
  $('progressBar').style.width = '0%';

  const start = await api(`/api/admin/events/${ev.id}/rush?strategy=${strategy}&buyers=${buyers}&concurrency=${concurrency}`,
    { method: 'POST' });
  if (!start.ok) { setRunning(false); return alert('Could not start: ' + errorText(start)); }
  setRunning(true, `running ${STRATEGIES[strategy].label}…`);

  let rush = start.data;
  let stats = null;
  while (true) {
    const [p, s] = await Promise.all([api('/api/admin/rush'), api(`/api/admin/events/${ev.id}/stats`)]);
    if (p.ok) rush = p.data;
    if (s.ok) { stats = s.data; renderStats(stats, rush); }
    if (!rush.running) break;
    await sleep(POLL_MS);
  }
  // Queue version: every buyer already has an answer, but the workers may still be writing orders.
  // Keep watching until the queue is empty and the DB has caught up with Redis.
  if (strategy === 'queue') {
    setRunning(true, 'buyers answered, workers catching up…');
    const until = Date.now() + 30_000;
    while (Date.now() < until) {
      const s = await api(`/api/admin/events/${ev.id}/stats`);
      if (s.ok) {
        stats = s.data;
        renderStats(stats, rush);
        if (stats.queueBacklog === 0 && stats.ordersCount + stats.expired >= rush.sold) break;
      }
      await sleep(POLL_MS);
    }
  }
  // One last stats read after the final orders committed.
  const final = await api(`/api/admin/events/${ev.id}/stats`);
  if (final.ok) { stats = final.data; renderStats(stats, rush); }
  $('progressBar').style.width = '100%';
  state.lastRush = rush;
  setRunning(false, 'finished');
  addHistory({ strategy, seats: ev.totalTickets, buyers, reqPerSec: rush.reqPerSec, p95: rush.p95Ms,
    sold: stats ? stats.ordersCount : rush.sold, oversold: stats ? stats.oversold : null, tooBusy: rush.tooBusy });
  refreshEventCard();
}

/** Between rushes, keep the seat map live (e.g. yellow seats turning grey when their hold expires). */
async function idleRefresh() {
  if (state.running || !state.eventId || !state.adminToken) return;
  const s = await api(`/api/admin/events/${state.eventId}/stats`);
  if (s.ok && !state.running && JSON.stringify(s.data) !== JSON.stringify(state.lastStats)) {
    renderStats(s.data, state.lastRush);
  }
}

function setRunning(running, label) {
  state.running = running;
  for (const id of ['startBtn', 'createEventBtn', 'eventSelect', 'refreshEventsBtn']) $(id).disabled = running;
  const tag = $('runState');
  tag.textContent = label || (running ? 'running' : 'idle');
  tag.className = 'tag ' + (running ? 'running' : label === 'finished' ? 'done' : '');
}

// ---------------------------------------------------------------- history
function loadHistory() {
  try { return JSON.parse(sessionStorage.getItem('tixrush.history') || '[]'); } catch { return []; }
}
function saveHistory() {
  try { sessionStorage.setItem('tixrush.history', JSON.stringify(state.history)); } catch { /* private mode */ }
}
function addHistory(row) { state.history.push(row); saveHistory(); renderHistory(); }

function renderHistory() {
  const body = $('historyBody');
  if (!state.history.length) {
    body.innerHTML = '<tr><td colspan="8" class="muted">Finished rushes show up here, so you can compare the 4 versions.</td></tr>';
    return;
  }
  const maxRps = Math.max(...state.history.map((h) => h.reqPerSec || 0), 1);
  const maxP95 = Math.max(...state.history.map((h) => h.p95 || 0), 1);
  body.innerHTML = state.history.map((h, i) => `
    <tr>
      <td>${i + 1}</td>
      <td><b>${STRATEGIES[h.strategy]?.label ?? h.strategy}</b></td>
      <td>${fmt(h.seats)}</td>
      <td>${fmt(h.buyers)}</td>
      <td><div class="barcell"><div class="bar" style="width:${(h.reqPerSec / maxRps) * 90}px"></div>${fmt(h.reqPerSec)}</div></td>
      <td><div class="barcell"><div class="bar slow" style="width:${((h.p95 || 0) / maxP95) * 90}px"></div>${ms(h.p95)}</div></td>
      <td>${fmt(h.sold)}${h.tooBusy ? ` <span class="muted">(${fmt(h.tooBusy)}×503)</span>` : ''}</td>
      <td>${h.oversold > 0 ? `<span class="bad-text">${fmt(h.oversold)} ❌</span>` : '<span class="ok-text">0 ✅</span>'}</td>
    </tr>`).join('');
}

// ---------------------------------------------------------------- fan view
async function fanAuth(kind) {
  const body = { email: $('fanEmail').value.trim(), password: $('fanPassword').value };
  const r = await api(`/api/auth/${kind}`, { method: 'POST', body, auth: 'none' });
  fanLog(r.status, `${kind}: ` + (r.ok ? `ok, got a JWT (role ${r.data.role})` : errorText(r)));
  if (r.ok) {
    state.fanToken = r.data.token;
    $('fanStatus').textContent = `Logged in as ${body.email} (USER). The token is kept in this page only.`;
    $('fanBuyBtn').disabled = false;
    $('fanDoubleBtn').disabled = false;
    refreshMyOrders();
  }
}

async function refreshEventCard() {
  const ev = currentEvent();
  const card = $('eventCard');
  if (!ev) { card.innerHTML = '<p class="muted">Pick an event above.</p>'; return; }
  const t0 = performance.now();
  const r = await api(`/api/events/${ev.id}`, { auth: 'none' });
  const took = Math.round(performance.now() - t0);
  if (!r.ok) { card.innerHTML = `<p class="bad">${errorText(r)}</p>`; return; }
  const e = r.data;
  card.innerHTML = `
    <div class="name">${escapeHtml(e.name)}</div>
    <div class="muted">${escapeHtml(e.venue)} · ${new Date(e.startsAt).toLocaleDateString()}</div>
    <div class="left">${fmt(e.availableTickets)} <span class="muted" style="font-size:13px">/ ${fmt(e.totalTickets)} left</span></div>
    <div class="muted" style="font-size:12px">loaded in ${took} ms</div>`;
}

let cooldownTimer = null;

/** One purchase request. Each click makes a fresh random Idempotency-Key, like a real shop would. */
function sendBuy(strategy, idempotencyKey) {
  const ev = currentEvent();
  return fetch(`/api/events/${ev.id}/purchases?strategy=${strategy}`, {
    method: 'POST',
    headers: { Authorization: 'Bearer ' + state.fanToken, 'Idempotency-Key': idempotencyKey },
  }).then(async (res) => {
    const text = await res.text();
    let data = null;
    try { data = text ? JSON.parse(text) : null; } catch { data = text; }
    return { ok: res.ok, status: res.status, data, headers: res.headers };
  });
}

const shortId = (uuid) => uuid ? uuid.slice(0, 8) : '';

async function fanBuy() {
  const ev = currentEvent();
  if (!ev) return alert('Pick an event first.');
  const strategy = $('fanStrategy').value;
  const r = await sendBuy(strategy, crypto.randomUUID());
  handleBuyResult(r, strategy);
  refreshEventCard();
  refreshMyOrders();
}

/** Sends the SAME request twice at the same moment with ONE Idempotency-Key: a simulated double-click. */
async function fanDoubleClick() {
  const ev = currentEvent();
  if (!ev) return alert('Pick an event first.');
  const strategy = $('fanStrategy').value;
  const key = crypto.randomUUID();
  fanLog('··', `double-click test: 2 requests, same Idempotency-Key ${shortId(key)}…`);
  const [a, b] = await Promise.all([sendBuy(strategy, key), sendBuy(strategy, key)]);
  handleBuyResult(a, strategy, 'click 1');
  handleBuyResult(b, strategy, 'click 2');
  if (a.ok && b.ok) {
    const same = (a.data.reservationId ?? a.data.orderId) === (b.data.reservationId ?? b.data.orderId);
    fanLog(same ? 200 : 500, same ? '✅ both clicks got the SAME order: only one ticket was taken'
      : '❌ two different orders were created');
  }
  refreshEventCard();
  refreshMyOrders();
}

function handleBuyResult(r, strategy, prefix = '') {
  const p = prefix ? prefix + ': ' : '';
  const replayed = r.headers?.get('Idempotent-Replayed') === 'true' ? ' (replayed: same answer as before, no new purchase)' : '';
  if (r.status === 202) {
    fanLog(202, `${p}you got a ticket! Order ${shortId(r.data.reservationId)}… is being created; pay before ${new Date(r.data.expiresAt).toLocaleTimeString()}${replayed}`);
  } else if (r.ok) {
    fanLog(r.status, `${p}ticket bought: order #${r.data.orderId} (${STRATEGIES[strategy].label})${replayed}`);
  } else if (r.status === 429) {
    const wait = Number(r.headers.get('Retry-After')) || 60;
    fanLog(r.status, `rate limited: ${errorText(r)} (Retry-After: ${wait}s)`);
    startCooldown(wait);
  } else {
    fanLog(r.status, p + errorText(r));
  }
}

// ---------------------------------------------------------------- fan: my orders (pay / countdown)
state.myOrders = [];

async function refreshMyOrders() {
  if (!state.fanToken) return;
  const r = await api('/api/orders/mine', { auth: 'fan' });
  if (r.ok) { state.myOrders = r.data; renderMyOrders(); }
}

function renderMyOrders() {
  const list = $('myOrders');
  if (!state.myOrders.length) {
    list.innerHTML = '<li class="muted">No orders yet.</li>';
    return;
  }
  const now = Date.now();
  list.innerHTML = state.myOrders.slice(0, 8).map((o) => {
    const id = o.reservationId ? shortId(o.reservationId) + '…' : '#' + o.orderId;
    let right = '';
    if (o.status === 'PENDING_PAYMENT') {
      const left = Math.max(0, Math.round((new Date(o.expiresAt).getTime() - now) / 1000));
      const mm = Math.floor(left / 60), ss = String(left % 60).padStart(2, '0');
      right = `<span class="countdown">${mm}:${ss}</span> <button class="primary small" data-pay="${o.reservationId}">Pay</button>`;
    }
    return `<li><span class="status s-${o.status}">${o.status.replace('_', ' ')}</span>
      <span class="muted">event ${o.eventId} · ${id}</span><span class="grow"></span>${right}</li>`;
  }).join('');
}

async function payOrder(reservationId) {
  const r = await api(`/api/orders/${reservationId}/pay`, { method: 'POST', auth: 'fan' });
  fanLog(r.status, r.ok ? `paid order ${shortId(reservationId)}… ✅ the seat is yours` : errorText(r));
  refreshMyOrders();
}

function startCooldown(seconds) {
  const btn = $('fanBuyBtn');
  clearInterval(cooldownTimer);
  let left = seconds;
  btn.disabled = true;
  btn.textContent = `Wait ${left}s`;
  cooldownTimer = setInterval(() => {
    left -= 1;
    if (left <= 0) {
      clearInterval(cooldownTimer);
      btn.disabled = !state.fanToken;
      btn.textContent = 'Buy ticket';
    } else {
      btn.textContent = `Wait ${left}s`;
    }
  }, 1000);
}

function fanLog(status, message) {
  const li = document.createElement('li');
  const cls = status === 429 ? 'c429' : status === 401 ? 'c401' : 'c' + String(status)[0];
  li.innerHTML = `<span class="muted">${new Date().toLocaleTimeString()}</span> <span class="code ${cls}">${status}</span> ${escapeHtml(message)}`;
  $('fanLog').prepend(li);
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// ---------------------------------------------------------------- redis ping
async function redisPing() {
  const btn = $('pingBtn');
  btn.textContent = 'Redis ping…';
  const r = await api('/api/admin/redis/ping');
  btn.textContent = r.ok ? `Redis ping: avg ${r.data.avgMillis} ms (min ${r.data.minMillis})` : 'Redis ping failed';
  btn.className = 'pill pill-btn ' + (r.ok ? 'ok' : 'err');
}

// ---------------------------------------------------------------- wire up
async function init() {
  renderStrategyCards();
  renderPath();
  renderHistory();
  renderCounters(null, null);

  $('eventSelect').addEventListener('change', onEventChanged);
  $('refreshEventsBtn').addEventListener('click', () => loadEvents());
  $('createEventBtn').addEventListener('click', createEvent);
  $('startBtn').addEventListener('click', startRush);
  $('clearHistoryBtn').addEventListener('click', () => { state.history = []; saveHistory(); renderHistory(); });
  $('fanRegisterBtn').addEventListener('click', () => fanAuth('register'));
  $('fanLoginBtn').addEventListener('click', () => fanAuth('login'));
  $('fanBuyBtn').addEventListener('click', fanBuy);
  $('fanDoubleBtn').addEventListener('click', fanDoubleClick);
  $('myOrders').addEventListener('click', (e) => {
    const id = e.target.dataset?.pay;
    if (id) payOrder(id);
  });
  // Countdown ticks every second; orders + seats refresh every 2 s so you can watch holds expire.
  setInterval(renderMyOrders, 1000);
  setInterval(() => { if (state.fanToken) refreshMyOrders(); }, 2000);
  setInterval(idleRefresh, 2000);
  $('fanRefreshBtn').addEventListener('click', refreshEventCard);
  $('pingBtn').addEventListener('click', redisPing);

  try {
    await adminLogin();
  } catch {
    return;
  }
  await loadEvents();

  // A rush already running (e.g. page reloaded mid-rush)? Show it as finished state at least.
  const p = await api('/api/admin/rush');
  if (p.ok && p.data.running) setRunning(false, 'a rush is running in the background');
}

init();
