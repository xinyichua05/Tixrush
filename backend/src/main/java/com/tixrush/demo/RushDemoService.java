package com.tixrush.demo;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import com.tixrush.common.ConflictException;
import com.tixrush.common.NotFoundException;
import com.tixrush.config.RedisConfig;
import com.tixrush.event.Event;
import com.tixrush.event.EventRepository;
import com.tixrush.event.SoldOutException;
import com.tixrush.event.TicketType;
import com.tixrush.event.TicketTypeRepository;
import com.tixrush.order.Order;
import com.tixrush.order.OrderRepository;
import com.tixrush.order.OrderResponse;
import com.tixrush.order.OrderService;
import com.tixrush.order.PaymentHoldPolicy;
import com.tixrush.order.RedisStockService;
import com.tixrush.order.SimBuyers;
import com.tixrush.order.TicketStockService;
import com.tixrush.order.TicketTypePurchaseService;
import com.tixrush.order.messaging.OrderPublisher;
import com.tixrush.seat.SeatRepository;
import com.tixrush.waitingroom.WaitingRoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The "Live Rush" director: a famous concert goes on sale and thousands of fans want in.
 *
 *   1. LOBBY      fans wait for the sale to open (bots + any real user who joins)
 *   2. OPEN       every 0.5 s the waiting room lets the next batch in (WaitingRoomService, real Redis);
 *                 admitted bots try to buy through the REAL buy path for zones (Redis reserve, best
 *                 available seats, RabbitMQ order), just like a real concert with VIP / CAT 1 / standing
 *   3. WAITING_FOR_RETURNS  every ticket is reserved; the line pauses until unpaid holds expire
 *                 (the RabbitMQ expiry worker gives the ticket back to Redis, and the line moves again)
 *   4. FINISHED   sold out for good, or nobody left in line
 *
 * Bots behave like people: some react slowly, some give up, some never pay.
 * Real users buy with the normal POST /api/events/{id}/purchases, which demands a waiting-room pass.
 *
 * Only one run at a time. Its state lives in memory here and is read by GET /api/rush-demo.
 */
@Service
public class RushDemoService {

    private static final Logger log = LoggerFactory.getLogger(RushDemoService.class);
    private static final long TICK_MS = 500;
    private static final int FEED_SIZE = 30;
    private static final Duration SAFETY_STOP = Duration.ofMinutes(5);

    // One letter per fan in the crowd map.
    static final char WAITING = 'W', BUYING = 'B', HOLDING = 'T', PAID = 'P', RETURNED = 'E',
            MISSED = 'X', GAVE_UP = 'G', PASS_EXPIRED = 'O';

    public enum Phase { IDLE, LOBBY, OPEN, WAITING_FOR_RETURNS, FINISHED }

    public record FeedItem(long atMs, String kind, String text) {
    }

    /** What the signed-in viewer sees about themselves. */
    /** clickedAfterMs: fastest-click runs only, how long after opening you clicked Join. */
    public record Me(String status, Integer number, Long ahead, Instant passExpiresAt, UUID reservationId,
                     Instant holdExpiresAt, Long clickedAfterMs) {
    }

    /** Live database check: orders that hold a seat must never exceed the tickets. */
    public record DbCheck(long activeOrders, long paidOrders, long pendingOrders, long expiredOrders, long oversold) {
    }

    /** Tickets still free in one zone, live from Redis (the zone list shows "12 left"). */
    public record ZoneLeft(Long ticketTypeId, String name, int left) {
    }

    /** dbCheck counts TICKETS (an order can hold 1-4). zones: what's left per zone right now. */
    public record State(Phase phase, String scenario, String scenarioLabel, Long eventId, String eventName,
                        String venue, Instant opensAt, long msSinceOpen, int fans, int tickets, int admitPerSecond,
                        long passSeconds, long holdSeconds, int ticketsLeft, int sold, int paid, int returned,
                        int missed, int gaveUp, int waiting, int lobby, int admitted, int stillComing,
                        Long soldOutAfterMs, Integer soldOutAtNumber, String crowd, Integer myIndex,
                        List<FeedItem> feed, Me me, DbCheck check, String endReason,
                        LineStyle lineStyle, Instant serverNow, List<ZoneLeft> zones) {
    }

    /** A zone of this run's practice event, as stored (its ticket type). */
    private record Zone(Long ticketTypeId, String name, boolean seated, int tickets) {
    }

    private record Setup(Long eventId, List<Zone> zones) {
    }

    private final WaitingRoomService waitingRoom;
    private final TicketTypePurchaseService ticketPurchase;
    private final TicketStockService ticketStock;
    private final TicketTypeRepository ticketTypeRepository;
    private final SeatRepository seatRepository;
    private final OrderService orderService;
    private final OrderRepository orderRepository;
    private final EventRepository eventRepository;
    private final RedisStockService redisStock;
    private final PaymentHoldPolicy holdPolicy;
    private final OrderPublisher publisher;
    private final SimBuyers simBuyers;
    private final TransactionTemplate tx;
    private final CacheManager cacheManager;

    private volatile Run current;

    public RushDemoService(WaitingRoomService waitingRoom, TicketTypePurchaseService ticketPurchase,
                           TicketStockService ticketStock, TicketTypeRepository ticketTypeRepository,
                           SeatRepository seatRepository, OrderService orderService,
                           OrderRepository orderRepository, EventRepository eventRepository,
                           RedisStockService redisStock, PaymentHoldPolicy holdPolicy, OrderPublisher publisher,
                           SimBuyers simBuyers, TransactionTemplate tx, CacheManager cacheManager) {
        this.waitingRoom = waitingRoom;
        this.ticketPurchase = ticketPurchase;
        this.ticketStock = ticketStock;
        this.ticketTypeRepository = ticketTypeRepository;
        this.seatRepository = seatRepository;
        this.orderService = orderService;
        this.orderRepository = orderRepository;
        this.eventRepository = eventRepository;
        this.redisStock = redisStock;
        this.holdPolicy = holdPolicy;
        this.publisher = publisher;
        this.simBuyers = simBuyers;
        this.tx = tx;
        this.cacheManager = cacheManager;
    }

    // ------------------------------------------------------------------ public API

    /** Starts a new run. Refused (409) while another run is still selling. */
    public synchronized State start(RushSettings settings, Long viewerId) {
        Run old = current;
        if (old != null && old.phase != Phase.FINISHED) {
            throw new ConflictException("A practice rush is already running. Join it, or wait until it ends.");
        }
        if (old != null) {
            old.shutdown(); // stop its leftover "watch unpaid holds" ticks
        }
        Setup setup = resetDemoEvent(settings);
        Run run = new Run(settings, setup.eventId(), setup.zones(), simBuyers.ids());
        current = run;
        run.begin();
        return state(viewerId);
    }

    /** A real user steps into the line (before opening: random place; after: the back of the line). */
    public State join(Long userId) {
        Run run = current;
        if (run == null || run.phase == Phase.FINISHED) {
            throw new ConflictException("No practice rush is running. Pick one to start.");
        }
        // Timestamp the click the moment it arrives, before any waiting: that's what decides your place.
        run.joinHuman(userId, Instant.now());
        return state(userId);
    }

    public State state(Long viewerId) {
        Run run = current;
        if (run == null) {
            return new State(Phase.IDLE, null, null, null, null, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, null, null, "", null, List.of(), null, null, null, null, Instant.now(), List.of());
        }
        return run.snapshot(viewerId);
    }

    /** Ends the current run right away, in any phase, so a new one can start immediately. Safe to call twice. */
    public State stop(Long viewerId) {
        Run run = current;
        if (run != null) {
            run.stopNow();
        }
        return state(viewerId);
    }

    public boolean isRunning() {
        Run run = current;
        return run != null && run.phase != Phase.FINISHED;
    }

    /** After a restart nothing is in memory: close every practice event's gate again. */
    @EventListener(ApplicationReadyEvent.class)
    public void gatePracticeEvents() {
        eventRepository.findByPracticeTrue().forEach(e -> waitingRoom.activate(e.getId()));
    }

    /** A real user bought with their pass (published by WaitingRoomService from PurchaseController). */
    @EventListener
    public void onPassUsed(WaitingRoomService.PassUsed used) {
        Run run = current;
        if (run != null && run.eventId.equals(used.eventId())) {
            run.humanBought(used.member(), used.order());
        }
    }

    // ------------------------------------------------------------------ setup

    /**
     * Finds (or creates) the demo event and resets it: no orders, the scenario's zones built fresh
     * (numbered seats included), fresh stock, nobody's 4-per-person count left over, empty line.
     */
    private Setup resetDemoEvent(RushSettings s) {
        Instant now = Instant.now();
        List<Long> oldTypeIds = new ArrayList<>();
        List<Zone> zones = new ArrayList<>();
        Long eventId = tx.execute(status -> {
            Event event = eventRepository.findFirstByNameOrderByIdAsc(s.eventName())
                    .orElseGet(() -> eventRepository.save(new Event(s.eventName(),
                            "Bukit Jalil National Stadium, Kuala Lumpur",
                            now.plus(120, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS), now, s.tickets())));
            event.markPractice(); // never shown in the real shop
            // Lesson learned: an order from the old run can still be on its way into the table while we reset.
            // 1. Delete the old run's orders.
            // 2. Move the sale start and write it NOW (flush): this UPDATE locks the event row. From here on,
            //    an old order waits for us and is then dropped by the guard in insertPendingIfAbsent.
            // 3. Delete again: catches an old order that slipped in between 1 and 2.
            // (Step 1 takes the old orders before the event row, the same order the expiry worker uses,
            //  so the two can't deadlock. Step 3 only meets orders that slipped in, which nobody else holds.)
            orderRepository.deleteByEventId(event.getId());
            event.useTicketTypes(null, s.tickets());
            event.restartSale(s.tickets(), now);
            eventRepository.flush();
            orderRepository.deleteByEventId(event.getId());

            // 4. This run's zones, built fresh: the old ones (and their seats, by cascade) go first.
            //    An order of the old run that is still on its way finds no ticket type any more and is dropped
            //    (insertPendingTicketsIfAbsent copies the type row, so no row = no insert).
            ticketTypeRepository.findByEventIdOrderBySortOrderAsc(event.getId()).forEach(t -> oldTypeIds.add(t.getId()));
            ticketTypeRepository.deleteByEventId(event.getId());
            int sort = 0;
            for (PracticeZone z : s.zones()) {
                TicketType type = ticketTypeRepository.save(new TicketType(event.getId(), z.name(), z.kind(), z.color(),
                        z.price(), z.tickets(), sort++, z.rows(), z.seatsPerRow()));
                if (z.isSeated()) {
                    seatRepository.generate(type.getId(), z.rows(), z.seatsPerRow());
                }
                zones.add(new Zone(type.getId(), z.name(), z.isSeated(), z.tickets()));
            }
            return event.getId();
        });
        if (eventId == null) {
            throw new NotFoundException("Something went wrong. Please try again.");
        }
        Cache cache = cacheManager.getCache(RedisConfig.EVENT_CACHE);
        if (cache != null) {
            cache.evict(eventId); // the event page shouldn't show the old run's numbers
        }
        redisStock.clear(eventId); // the old single-pool counter, from before practice had zones
        ticketStock.clear(eventId, oldTypeIds);
        ticketStock.clearAllBought(eventId); // the same fans come back every run: reset their 4-ticket limit
        zones.forEach(z -> ticketStock.prime(eventId, z.ticketTypeId())); // live counts from second 0
        holdPolicy.override(eventId, s.hold());
        return new Setup(eventId, List.copyOf(zones));
    }

    // ------------------------------------------------------------------ one run

    /** One fan in the crowd. Status changes go through move() so two threads can't both "win" a change. */
    private static final class Fan {
        final String member;
        final Long userId;
        final boolean human;
        final double score;
        final long arrivalMs;   // late fans: when they show up (ms after opening); lobby fans: 0
        final boolean willBuy;
        final boolean willPay;
        final long reactionMs;
        final long payDelayMs;

        volatile char status = WAITING;
        volatile int number;    // place in line, 1 = first (set when the sale opens / when they arrive)
        volatile UUID reservationId;
        volatile Instant holdExpiresAt;
        volatile Instant passExpiresAt;
        volatile Long clickedAfterMs; // real users in a fastest-click run
        int favouriteZone;            // bots: the zone they want (index into Run.zones)
        int wants = 1;                // bots: how many tickets they want (friends come along)
        volatile int got;             // tickets in this fan's order (1-4), once they have one
        volatile boolean cancelled;   // gave the tickets back with "Cancel order" (not a hold that ran out)

        Fan(String member, Long userId, boolean human, double score, long arrivalMs, boolean willBuy,
            boolean willPay, long reactionMs, long payDelayMs) {
            this.member = member;
            this.userId = userId;
            this.human = human;
            this.score = score;
            this.arrivalMs = arrivalMs;
            this.willBuy = willBuy;
            this.willPay = willPay;
            this.reactionMs = reactionMs;
            this.payDelayMs = payDelayMs;
        }

        synchronized boolean move(char from, char to) {
            if (status != from) {
                return false;
            }
            status = to;
            return true;
        }
    }

    private final class Run {
        final RushSettings s;
        final Long eventId;
        final String venue;
        final Instant startedAt = Instant.now();
        final Instant opensAt;
        final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("rush-demo-tick").daemon().factory());
        final ScheduledExecutorService bots = Executors.newScheduledThreadPool(24,
                Thread.ofPlatform().name("rush-demo-bot-", 0).daemon().factory());

        final Map<String, Fan> byMember = new ConcurrentHashMap<>();
        final Map<Long, Fan> humans = new ConcurrentHashMap<>();
        final List<Fan> lobbyFans = new ArrayList<>();          // guarded by "this"
        final List<Fan> lateFans = new ArrayList<>();           // sorted by arrival, guarded by "this"
        int lateArrived;                                        // guarded by "this"
        final List<Fan> line = new CopyOnWriteArrayList<>();    // everyone in line order (after opening)

        final AtomicInteger sold = new AtomicInteger(), paid = new AtomicInteger(), returned = new AtomicInteger(),
                missed = new AtomicInteger(), gaveUp = new AtomicInteger(), admitted = new AtomicInteger();
        final AtomicInteger missedSinceTick = new AtomicInteger(), gaveUpSinceTick = new AtomicInteger();
        final Deque<FeedItem> feed = new ArrayDeque<>();        // guarded by itself

        final List<Zone> zones;
        volatile Phase phase = Phase.LOBBY;
        volatile int ticketsLeft;
        volatile List<ZoneLeft> zoneLeft;                       // refreshed every tick, from Redis
        volatile Long soldOutAfterMs;
        volatile Integer soldOutAtNumber;
        volatile DbCheck check;
        volatile String endReason;
        volatile Instant finishedAt;
        int calmTicks;                                          // only touched by the ticker thread

        Run(RushSettings s, Long eventId, List<Zone> zones, List<Long> simIds) {
            this.s = s;
            this.eventId = eventId;
            this.zones = zones;
            this.venue = eventRepository.findById(eventId).map(Event::getVenue).orElse("");
            this.opensAt = startedAt.plus(s.lobby());
            this.ticketsLeft = s.tickets();
            this.zoneLeft = zones.stream().map(z -> new ZoneLeft(z.ticketTypeId(), z.name(), z.tickets())).toList();
            createBots(simIds);
        }

        /** A zone picked at random, bigger zones more often (that's where most fans want to be). */
        private int randomZone(ThreadLocalRandom rnd) {
            int pick = rnd.nextInt(Math.max(1, s.tickets()));
            for (int i = 0; i < zones.size(); i++) {
                pick -= zones.get(i).tickets();
                if (pick < 0) {
                    return i;
                }
            }
            return 0;
        }

        /** Rolls the dice for every bot once, up front: who's early, who's slow, who never pays. */
        private void createBots(List<Long> simIds) {
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            int lobbyCount = (int) Math.round(s.fans() * s.lobbyShare());
            for (int i = 1; i <= s.fans(); i++) {
                boolean early = i <= lobbyCount;
                long window = Math.max(1, s.lateWindow().toMillis());
                long arrival = early ? 0
                        : s.lineStyle() == LineStyle.FASTEST_CLICK ? ClickSpeed.sample(rnd, window) // a race to click
                        : rnd.nextLong(window);                                                      // just turning up late
                Fan fan = new Fan(WaitingRoomService.botMember(i), simIds.get(i % simIds.size()), false,
                        early ? rnd.nextDouble() : 0, arrival,
                        rnd.nextDouble() < s.buyChance(), rnd.nextDouble() < s.payChance(),
                        200 + rnd.nextLong(Math.max(1, s.maxReaction().toMillis() - 200)),
                        s.minPayDelay().toMillis()
                                + rnd.nextLong(Math.max(1, s.maxPayDelay().toMillis() - s.minPayDelay().toMillis())));
                fan.favouriteZone = randomZone(rnd);
                fan.wants = rnd.nextDouble() < 0.25 ? 2 : 1; // most come alone, some bring a friend
                byMember.put(fan.member, fan);
                (early ? lobbyFans : lateFans).add(fan);
            }
            lateFans.sort(Comparator.comparingLong(f -> f.arrivalMs));
        }

        void begin() {
            // Everyone already in the lobby goes into the Redis line in ONE command, with random scores.
            Map<String, Double> scores = new LinkedHashMap<>();
            for (Fan f : lobbyFans) {
                scores.put(f.member, f.score);
            }
            waitingRoom.clear(eventId, List.of());
            waitingRoom.addAll(eventId, scores);
            waitingRoom.activate(eventId); // from now on, buying this event needs a pass
            note("info", s.lineStyle() == LineStyle.FASTEST_CLICK
                    ? String.format("%,d fans are ready to click. The sale opens in %d seconds.",
                            s.fans(), s.lobby().toSeconds())
                    : String.format("%,d fans are in the lobby. The sale opens in %d seconds.",
                            lobbyFans.size(), s.lobby().toSeconds()));
            // Declaring the payment-hold queue on CloudAMQP takes a moment: do it during the lobby.
            bots.execute(() -> {
                try {
                    publisher.ensureHoldQueue(s.hold().toSeconds());
                } catch (RuntimeException e) {
                    log.warn("Could not declare the hold queue: {}", e.getMessage());
                }
            });
            ticker.scheduleWithFixedDelay(this::safeTick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
            log.info("Live rush started: {} ({} fans, {} tickets, event {})", s.label(), s.fans(), s.tickets(), eventId);
        }

        void shutdown() {
            ticker.shutdownNow();
            bots.shutdownNow();
        }

        /**
         * Stop button: end now, and WAIT until no bot is still mid-purchase, so nothing from this run
         * can sneak into the next one. (Orders already on their way are dropped by the stale-order guard
         * in OrderRepository.insertPendingIfAbsent.)
         */
        void stopNow() {
            finish("stopped");
            shutdown();
            try {
                ticker.awaitTermination(3, TimeUnit.SECONDS);
                bots.awaitTermination(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        // ---------------- real users

        /**
         * A real user clicks Join at `clickedAt`. The line order is decided in memory under the lock
         * (fast, no network); the Redis writes happen after, so a slow network never delays a click.
         */
        void joinHuman(Long userId, Instant clickedAt) {
            Map<String, Double> botsAhead;
            String member = WaitingRoomService.userMember(userId);
            double score;
            synchronized (this) {
                if (humans.containsKey(userId)) {
                    return; // clicking again never changes your place
                }
                boolean opened = !clickedAt.isBefore(opensAt);
                if (s.lineStyle() == LineStyle.FASTEST_CLICK && !opened) {
                    throw new ConflictException("Too early! The sale hasn't opened yet.");
                }
                if (phase == Phase.LOBBY && opened) {
                    open(); // the heartbeat hasn't noticed yet, but the clock says it's open
                }
                long since = Math.max(0, Duration.between(opensAt, clickedAt).toMillis());
                // Every fan who arrived before your click is ahead of you, even between two heartbeats.
                botsAhead = takeArrivals(since);

                boolean lobby = phase == Phase.LOBBY;
                score = lobby ? ThreadLocalRandom.current().nextDouble() : 1 + since;
                Fan fan = new Fan(member, userId, true, score, 0, true, true, 0, 0);
                byMember.put(member, fan);
                humans.put(userId, fan);
                if (lobby) {
                    lobbyFans.add(fan);
                    note("you", "You're in the lobby. Your place in line is drawn when the sale opens.");
                } else {
                    fan.number = line.size() + 1;
                    line.add(fan);
                    if (s.lineStyle() == LineStyle.FASTEST_CLICK) {
                        fan.clickedAfterMs = since;
                        note("you", String.format("You clicked %.2f s after the sale opened: you're #%,d in line.",
                                since / 1000.0, fan.number));
                    } else {
                        note("you", String.format("You joined the back of the line as #%,d.", fan.number));
                    }
                }
            }
            waitingRoom.addAll(eventId, botsAhead);
            waitingRoom.add(eventId, member, score);
        }

        void humanBought(String member, OrderResponse order) {
            Fan fan = byMember.get(member);
            if (fan == null) {
                return;
            }
            fan.reservationId = order.reservationId();
            fan.holdExpiresAt = order.expiresAt();
            fan.got = Math.max(1, order.quantity());
            if (fan.move(BUYING, HOLDING) || fan.move(PASS_EXPIRED, HOLDING)) {
                sold.addAndGet(fan.got);
                note("you", String.format("You got %s! Pay within %d seconds or %s to the next fan.",
                        describe(order), s.hold().toSeconds(), fan.got == 1 ? "it goes" : "they go"));
            }
        }

        // ---------------- the heartbeat

        void safeTick() {
            try {
                tick();
            } catch (RuntimeException e) {
                log.warn("Rush tick failed: {}", e.toString()); // keep ticking; next tick usually works
            }
        }

        void tick() {
            Instant now = Instant.now();
            if (phase == Phase.LOBBY) {
                if (now.isBefore(opensAt)) {
                    return;
                }
                open();
            }
            checkHolds(now);
            refreshDbCheck();
            if (phase == Phase.FINISHED) {
                // After the end we only keep watching unpaid holds, so the numbers settle.
                if (countStatus(HOLDING) == 0 || now.isAfter(finishedAt.plus(Duration.ofMinutes(2)))) {
                    shutdown();
                }
                return;
            }

            addLateArrivals();
            refreshZoneLeft();

            if (ticketsLeft > 0) {
                if (phase == Phase.WAITING_FOR_RETURNS) {
                    phase = Phase.OPEN;
                    note("info", ticketsLeft == 1 ? "A ticket came back! The line is moving again."
                            : String.format("%d tickets came back! The line is moving again.", ticketsLeft));
                }
                admitBatch(now);
            } else {
                if (soldOutAfterMs == null) {
                    soldOutAfterMs = msSinceOpen();
                    soldOutAtNumber = admitted.get();
                }
                if (phase == Phase.OPEN) {
                    phase = Phase.WAITING_FOR_RETURNS;
                    note("soldout", "Every ticket is reserved. The line is paused until unpaid tickets come back.");
                }
            }
            expireHumanPasses(now);
            flushAggregates();
            maybeFinish(now);
        }

        /** What's left in every zone right now (the real Redis counters), and in total. */
        void refreshZoneLeft() {
            List<ZoneLeft> now = new ArrayList<>(zones.size());
            int total = 0;
            for (Zone z : zones) {
                Integer stock = ticketStock.getStock(eventId, z.ticketTypeId());
                int left = stock == null ? 0 : Math.max(0, stock);
                now.add(new ZoneLeft(z.ticketTypeId(), z.name(), left));
                total += left;
            }
            zoneLeft = List.copyOf(now);
            ticketsLeft = total;
        }

        /** Sale opens: the lobby (random scores) becomes the front of the line. */
        synchronized void open() {
            if (phase != Phase.LOBBY) {
                return; // already opened (by the heartbeat or by an early click)
            }
            lobbyFans.sort(Comparator.comparingDouble(f -> f.score));
            for (Fan f : lobbyFans) {
                f.number = line.size() + 1;
                line.add(f);
            }
            phase = Phase.OPEN;
            note("open", s.lineStyle() == LineStyle.FASTEST_CLICK
                    ? "The sale is open! Fans are clicking Join as fast as they can."
                    : String.format("The sale is open! Letting fans in, %d per second.", s.admitPerSecond()));
        }

        /** Late fans whose arrival time has come join the back of the line (Redis write outside the lock). */
        void addLateArrivals() {
            Map<String, Double> scores;
            synchronized (this) {
                scores = takeArrivals(msSinceOpen());
            }
            waitingRoom.addAll(eventId, scores);
        }

        /** Puts every late fan who arrived by `untilMs` into the in-memory line. Call while holding the lock. */
        private Map<String, Double> takeArrivals(long untilMs) {
            Map<String, Double> scores = new LinkedHashMap<>();
            while (lateArrived < lateFans.size() && lateFans.get(lateArrived).arrivalMs <= untilMs) {
                Fan f = lateFans.get(lateArrived++);
                f.number = line.size() + 1;
                line.add(f);
                scores.put(f.member, 1 + f.arrivalMs + scores.size() / 1_000_000.0); // tiny tie-breaker
            }
            return scores;
        }

        /**
         * Lets in up to admitPerSecond/2 fans, but never more than 2 per ticket that might still be free
         * (counting fans who are already buying). Otherwise one returned ticket would pull 25 people
         * out of the line, 24 of whom would just miss out: "flow control", like real waiting rooms do.
         */
        void admitBatch(Instant now) {
            int batch = Math.max(1, (int) Math.round(s.admitPerSecond() * TICK_MS / 1000.0));
            int room = ticketsLeft * 2 - countStatus(BUYING);
            batch = Math.min(batch, room);
            if (batch <= 0) {
                return;
            }
            for (String member : waitingRoom.admit(eventId, batch, s.pass())) {
                Fan fan = byMember.get(member);
                if (fan == null || !fan.move(WAITING, BUYING)) {
                    continue;
                }
                admitted.incrementAndGet();
                if (fan.human) {
                    fan.passExpiresAt = now.plus(s.pass());
                    note("you", String.format("It's your turn! You have %d seconds to buy.", s.pass().toSeconds()));
                } else {
                    bots.schedule(() -> botTurn(fan), fan.reactionMs, TimeUnit.MILLISECONDS);
                }
            }
        }

        // ---------------- bots

        void botTurn(Fan fan) {
            if (!fan.willBuy) {
                if (fan.move(BUYING, GAVE_UP)) {
                    gaveUp.incrementAndGet();
                    gaveUpSinceTick.incrementAndGet();
                }
                return;
            }
            // Like a real fan: try the favourite zone first. Gone? Settle for another zone that still has tickets.
            Zone favourite = zones.get(fan.favouriteZone);
            OrderResponse order = tryZone(fan, favourite);
            if (order == null) {
                List<Zone> others = zoneLeft.stream()
                        .filter(z -> z.left() > 0 && !z.ticketTypeId().equals(favourite.ticketTypeId()))
                        .map(z -> zones.stream().filter(x -> x.ticketTypeId().equals(z.ticketTypeId())).findFirst().orElseThrow())
                        .toList();
                if (!others.isEmpty()) {
                    order = tryZone(fan, others.get(ThreadLocalRandom.current().nextInt(others.size())));
                }
            }
            if (order == null) {
                markMissed(fan);
                return;
            }
            fan.reservationId = order.reservationId();
            fan.holdExpiresAt = order.expiresAt();
            fan.got = Math.max(1, order.quantity());
            if (fan.move(BUYING, HOLDING)) {
                sold.addAndGet(fan.got);
                note("sold", String.format("Fan #%,d got %s", fan.number, describe(order)));
            }
            if (fan.willPay) {
                bots.schedule(() -> botPay(fan, 1), fan.payDelayMs, TimeUnit.MILLISECONDS);
            }
        }

        /**
         * One attempt in one zone, through the REAL buy path for zones (Redis + seats + RabbitMQ).
         * Seated zones use "best available", so the seat map fills from the front row.
         * Only 1 left but wanted 2? Then take 1. Returns null if nothing could be had here.
         */
        private OrderResponse tryZone(Fan fan, Zone zone) {
            int want = fan.wants;
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    return ticketPurchase.purchase(eventId, fan.userId, zone.ticketTypeId(), want, null);
                } catch (SoldOutException e) {
                    return null;
                } catch (ConflictException e) {
                    if (want == 1) {
                        return null; // e.g. the seats someone else just took
                    }
                    want = 1; // "Only 1 ticket of this type left": go alone
                } catch (RuntimeException e) {
                    log.debug("Bot purchase failed: {}", e.toString());
                    return null;
                }
            }
            return null;
        }

        /** "2 × VIP (Row A)" / "1 × Rock Zone", for the feed. */
        private String describe(OrderResponse order) {
            String zoneName = zones.stream().filter(z -> z.ticketTypeId().equals(order.ticketTypeId()))
                    .map(Zone::name).findFirst().orElse("ticket");
            String text = Math.max(1, order.quantity()) + " × " + zoneName;
            return order.seats() == null || order.seats().isEmpty() ? text : text + " (Row " + order.seats().getFirst().row() + ")";
        }

        void botPay(Fan fan, int attempt) {
            try {
                orderService.pay(fan.reservationId, fan.userId);
                if (fan.move(HOLDING, PAID)) {
                    paid.addAndGet(fan.got);
                    note("paid", String.format("Fan #%,d paid", fan.number));
                }
            } catch (NotFoundException e) {
                // The worker hasn't written the order yet (it's still in the RabbitMQ queue). Try again soon.
                if (attempt < 15 && !bots.isShutdown()) {
                    bots.schedule(() -> botPay(fan, attempt + 1), 1, TimeUnit.SECONDS);
                }
            } catch (RuntimeException e) {
                log.debug("Bot payment failed: {}", e.toString()); // e.g. the hold ran out first
            }
        }

        void markMissed(Fan fan) {
            if (fan.move(BUYING, MISSED)) {
                missed.incrementAndGet();
                missedSinceTick.incrementAndGet();
            }
        }

        // ---------------- holds & passes

        /** Looks up tickets whose payment hold is over (and the real users' orders) in the DB. */
        void checkHolds(Instant now) {
            List<Fan> toCheck = new ArrayList<>();
            for (Fan f : byMember.values()) {
                if (f.status == HOLDING && f.reservationId != null
                        && (f.human || (f.holdExpiresAt != null && now.isAfter(f.holdExpiresAt.plusSeconds(1))))) {
                    toCheck.add(f);
                }
            }
            if (toCheck.isEmpty()) {
                return;
            }
            Map<UUID, Fan> byReservation = new LinkedHashMap<>();
            toCheck.forEach(f -> byReservation.put(f.reservationId, f));
            for (Order order : orderRepository.findByReservationIdIn(byReservation.keySet())) {
                Fan f = byReservation.get(order.getReservationId());
                if (Order.RELEASED.contains(order.getStatus()) && f.move(HOLDING, RETURNED)) {
                    returned.addAndGet(f.got);
                    boolean cancelled = Order.CANCELLED.equals(order.getStatus());
                    f.cancelled = cancelled;
                    String yours = f.got == 1 ? "your ticket" : "your tickets";
                    note(f.human ? "you" : "returned", f.human
                            ? (cancelled ? "You cancelled your order, so " + yours + " went to the next fans in line."
                                         : "Your payment hold ran out, so " + yours + " went to the next fans in line.")
                            : String.format("Fan #%,d didn't pay in time: %d %s back on sale", f.number, f.got,
                                    f.got == 1 ? "ticket is" : "tickets are"));
                } else if (Order.PAID.equals(order.getStatus()) && f.move(HOLDING, PAID)) {
                    paid.addAndGet(f.got);
                    if (f.human) {
                        note("you", "You paid. Enjoy the show!");
                    }
                }
            }
        }

        void expireHumanPasses(Instant now) {
            for (Fan f : humans.values()) {
                if (f.status == BUYING && f.passExpiresAt != null && now.isAfter(f.passExpiresAt)
                        && f.move(BUYING, PASS_EXPIRED)) {
                    note("you", "Your turn ran out before you bought a ticket.");
                }
            }
        }

        void refreshDbCheck() {
            long active = orderRepository.sumQuantityByEventIdAndStatusNotIn(eventId, Order.RELEASED); // tickets held
            check = new DbCheck(active,
                    orderRepository.countByEventIdAndStatus(eventId, Order.PAID),
                    orderRepository.countByEventIdAndStatus(eventId, Order.PENDING_PAYMENT),
                    orderRepository.countByEventIdAndStatusIn(eventId, Order.RELEASED),
                    Math.max(0, active - s.tickets()));
        }

        /** Bots that missed or gave up are summed per tick, otherwise the feed would be a waterfall. */
        void flushAggregates() {
            int m = missedSinceTick.getAndSet(0);
            if (m > 0) {
                note("missed", m == 1 ? "1 fan reached the front, but the tickets were already gone"
                        : String.format("%d fans reached the front, but the tickets were already gone", m));
            }
            int g = gaveUpSinceTick.getAndSet(0);
            if (g > 0) {
                note("info", g == 1 ? "1 fan gave up and left" : String.format("%d fans gave up and left", g));
            }
        }

        // ---------------- the end

        void maybeFinish(Instant now) {
            int botsBuying = 0, holding = 0, waiting = 0;
            for (Fan f : byMember.values()) {
                switch (f.status) {
                    case BUYING -> { if (!f.human) botsBuying++; }
                    case HOLDING -> holding++;
                    case WAITING -> { if (f.number > 0) waiting++; }
                    default -> { }
                }
            }
            boolean allArrived;
            synchronized (this) {
                allArrived = lateArrived == lateFans.size();
            }
            // Sold out for good: no stock, nobody mid-purchase, and no unpaid hold that could still come back.
            boolean soldOutForGood = ticketsLeft == 0 && botsBuying == 0 && holding == 0;
            // Or simply nobody left to serve.
            boolean lineDone = allArrived && waiting == 0 && botsBuying == 0;

            // Wait 2 s of "calm" first: an expired order gives its ticket back to Redis a moment AFTER the DB.
            calmTicks = (soldOutForGood || lineDone) ? calmTicks + 1 : 0;
            boolean timeUp = Duration.between(startedAt, now).compareTo(SAFETY_STOP) > 0;
            if (calmTicks >= 4 || timeUp) {
                finish(timeUp ? "time" : soldOutForGood ? "soldout" : "line-empty");
            }
        }

        synchronized void finish(String reason) {
            if (phase == Phase.FINISHED) {
                return; // already over (e.g. Stop pressed right as it ended by itself)
            }
            int leftInLine = 0;
            for (Fan f : line) {
                if (f.move(WAITING, MISSED)) {
                    leftInLine++;
                    missed.incrementAndGet();
                }
            }
            boolean stopped = "stopped".equals(reason);
            for (Fan f : humans.values()) {
                // Sold out = your turn can't lead anywhere. Stopped = the run is simply over.
                if ((ticketsLeft == 0 || stopped) && f.move(BUYING, MISSED)) {
                    missed.incrementAndGet();
                }
            }
            lateArrived = lateFans.size(); // late fans who never showed up just don't come any more
            endReason = reason;
            finishedAt = Instant.now();
            phase = Phase.FINISHED;
            // The gate stays ON: practice tickets can only be had during a practice run.
            waitingRoom.clear(eventId, humans.values().stream().map(f -> f.member).toList());
            note("end", switch (reason) {
                case "soldout" -> leftInLine == 0 ? "SOLD OUT. Every ticket found a fan."
                        : String.format("SOLD OUT. The %,d fans still in line missed out.", leftInLine);
                case "line-empty" -> ticketsLeft > 0
                        ? String.format("Everyone has been served. %d tickets are still unsold.", ticketsLeft)
                        : "Everyone has been served.";
                case "stopped" -> "The practice rush was stopped.";
                default -> "The demo stopped after 5 minutes.";
            });
            log.info("Live rush finished ({}): sold={} paid={} returned={} missed={} gaveUp={} check={}",
                    reason, sold.get(), paid.get(), returned.get(), missed.get(), gaveUp.get(), check);
        }

        // ---------------- reading the state

        long msSinceOpen() {
            return Math.max(0, Duration.between(opensAt, Instant.now()).toMillis());
        }

        int countStatus(char status) {
            int n = 0;
            for (Fan f : byMember.values()) {
                if (f.status == status) n++;
            }
            return n;
        }

        void note(String kind, String text) {
            long at = Duration.between(opensAt, Instant.now()).toMillis(); // negative = before opening
            synchronized (feed) {
                feed.addFirst(new FeedItem(at, kind, text));
                while (feed.size() > FEED_SIZE) {
                    feed.removeLast();
                }
            }
        }

        State snapshot(Long viewerId) {
            Fan me = viewerId == null ? null : humans.get(viewerId);
            int lobby;
            synchronized (this) {
                lobby = phase == Phase.LOBBY ? lobbyFans.size() : 0;
            }
            StringBuilder crowd = new StringBuilder(line.size());
            Integer myIndex = null;
            int waiting = 0;
            for (int i = 0; i < line.size(); i++) {
                Fan f = line.get(i);
                char c = f.status;
                crowd.append(c);
                if (c == WAITING) waiting++;
                if (f == me) myIndex = i;
            }
            if (phase == Phase.LOBBY) {
                crowd.append(String.valueOf(WAITING).repeat(lobby)); // order not drawn yet
            }
            List<FeedItem> feedCopy;
            synchronized (feed) {
                feedCopy = List.copyOf(feed);
            }
            int stillComing;
            synchronized (this) {
                stillComing = lateFans.size() - lateArrived;
            }
            return new State(phase, s.scenario(), s.label(), eventId, s.eventName(), venue, opensAt,
                    phase == Phase.LOBBY ? 0 : msSinceOpen(), s.fans(), s.tickets(), s.admitPerSecond(),
                    s.pass().toSeconds(), s.hold().toSeconds(), ticketsLeft, sold.get(), paid.get(), returned.get(),
                    missed.get(), gaveUp.get(), waiting, lobby, admitted.get(), stillComing, soldOutAfterMs,
                    soldOutAtNumber, crowd.toString(), myIndex, feedCopy, me == null ? null : meOf(me), check,
                    endReason, s.lineStyle(), Instant.now(), zoneLeft);
        }

        private Me meOf(Fan f) {
            String status = switch (f.status) {
                case WAITING -> phase == Phase.LOBBY ? "LOBBY" : "WAITING";
                case BUYING -> "YOUR_TURN";
                case HOLDING -> "BOUGHT";
                case PAID -> "PAID";
                case RETURNED -> f.cancelled ? "CANCELLED" : "HOLD_EXPIRED";
                case PASS_EXPIRED -> "PASS_EXPIRED";
                default -> "MISSED";
            };
            // "How many are in front of me" comes straight from Redis: ZRANK on the real line.
            Long ahead = f.status == WAITING && phase != Phase.LOBBY ? waitingRoom.ahead(eventId, f.member) : null;
            return new Me(status, f.number > 0 ? f.number : null, ahead, f.passExpiresAt, f.reservationId,
                    f.holdExpiresAt, f.clickedAfterMs);
        }
    }
}
