package com.tixrush.order;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.tixrush.common.TooBusyException;
import com.tixrush.event.SoldOutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs a "flash sale" INSIDE the server, so the visualizer page can watch it.
 *
 * Why not fire the buyers from the browser? A browser only opens about 6 connections per host,
 * so "2,000 buyers" would really be 6 at a time. Here a thread pool of `concurrency` threads
 * calls PurchaseService directly: a real race, like PurchaseConcurrencyTest and k6.
 *
 * Only one rush runs at a time. The page polls progress() a few times per second.
 * The rate limiter is NOT applied (same as the bench profile): it would block the shared buyers.
 */
/** Developer tool: only exists when tixrush.dev-tools.enabled=true. */
@ConditionalOnProperty(name = "tixrush.dev-tools.enabled", havingValue = "true")
@Service
public class RushSimulationService {

    private static final Logger log = LoggerFactory.getLogger(RushSimulationService.class);
    /** What the page sees. Latency percentiles are null until the rush has finished. */
    public record RushProgress(boolean running, Long eventId, String strategy, int buyers, int concurrency,
                               long done, long sold, long soldOut, long tooBusy, long errors, String lastError,
                               long elapsedMs, double reqPerSec, Double p50Ms, Double p95Ms, Double p99Ms) {
    }

    private final PurchaseService purchaseService;
    private final SimBuyers simBuyers;

    private volatile Rush current; // the running rush, or the last finished one

    public RushSimulationService(PurchaseService purchaseService, SimBuyers simBuyers) {
        this.purchaseService = purchaseService;
        this.simBuyers = simBuyers;
    }

    /** Starts a rush in the background and returns right away. False if one is already running. */
    public synchronized boolean start(Long eventId, String strategy, int buyers, int concurrency) {
        if (current != null && current.running) {
            return false;
        }
        List<Long> buyerIds = simBuyers.ids();
        Rush rush = new Rush(eventId, strategy.toLowerCase(), buyers, concurrency, buyerIds);
        current = rush;
        Thread.ofPlatform().name("rush-coordinator").daemon().start(rush::run);
        return true;
    }

    public RushProgress progress() {
        Rush rush = current;
        if (rush == null) {
            return new RushProgress(false, null, null, 0, 0, 0, 0, 0, 0, 0, null, 0, 0, null, null, null);
        }
        return rush.snapshot();
    }

    public boolean isRunning() {
        Rush rush = current;
        return rush != null && rush.running;
    }

    /** One run: counters are atomic because 100 threads update them at the same time. */
    private final class Rush {
        final Long eventId;
        final String strategy;
        final int buyers;
        final int concurrency;
        final List<Long> buyerIds;

        final AtomicLong done = new AtomicLong();
        final AtomicLong sold = new AtomicLong();
        final AtomicLong soldOut = new AtomicLong();
        final AtomicLong tooBusy = new AtomicLong();
        final AtomicLong errors = new AtomicLong();
        volatile String lastError;

        // Each buyer writes its own slot once, so no locking is needed. Read only after all threads finished.
        final long[] latencyNanos;
        final AtomicInteger latencyIndex = new AtomicInteger();

        volatile boolean running = true;
        final long startNanos = System.nanoTime();
        volatile long endNanos;
        volatile Double p50, p95, p99;

        Rush(Long eventId, String strategy, int buyers, int concurrency, List<Long> buyerIds) {
            this.eventId = eventId;
            this.strategy = strategy;
            this.buyers = buyers;
            this.concurrency = concurrency;
            this.buyerIds = buyerIds;
            this.latencyNanos = new long[buyers];
        }

        void run() {
            ExecutorService pool = Executors.newFixedThreadPool(concurrency);
            try {
                for (int i = 0; i < buyers; i++) {
                    Long userId = buyerIds.get(i % buyerIds.size());
                    pool.submit(() -> buyOnce(userId));
                }
                pool.shutdown();
                if (!pool.awaitTermination(5, TimeUnit.MINUTES)) {
                    pool.shutdownNow();
                    lastError = "Rush timed out after 5 minutes";
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            } finally {
                endNanos = System.nanoTime();
                computePercentiles();
                running = false;
                log.info("Rush finished: {}", snapshot());
            }
        }

        void buyOnce(Long userId) {
            long t0 = System.nanoTime();
            try {
                purchaseService.purchase(strategy, eventId, userId);
                sold.incrementAndGet();
            } catch (SoldOutException e) {
                soldOut.incrementAndGet();
            } catch (TooBusyException e) {
                tooBusy.incrementAndGet();
            } catch (Exception e) {
                errors.incrementAndGet();
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            } finally {
                latencyNanos[latencyIndex.getAndIncrement()] = System.nanoTime() - t0;
                done.incrementAndGet();
            }
        }

        void computePercentiles() {
            int n = Math.min(latencyIndex.get(), latencyNanos.length);
            if (n == 0) return;
            long[] sorted = Arrays.copyOf(latencyNanos, n);
            Arrays.sort(sorted);
            p50 = percentile(sorted, 0.50);
            p95 = percentile(sorted, 0.95);
            p99 = percentile(sorted, 0.99);
        }

        RushProgress snapshot() {
            long end = running ? System.nanoTime() : endNanos;
            long elapsedMs = Math.max(1, (end - startNanos) / 1_000_000);
            long finished = done.get();
            double reqPerSec = Math.round(finished * 10_000.0 / elapsedMs) / 10.0;
            return new RushProgress(running, eventId, strategy, buyers, concurrency, finished, sold.get(),
                    soldOut.get(), tooBusy.get(), errors.get(), lastError, elapsedMs, reqPerSec, p50, p95, p99);
        }
    }

    private static Double percentile(long[] sorted, double p) {
        int index = (int) Math.ceil(p * sorted.length) - 1;
        long nanos = sorted[Math.max(0, Math.min(index, sorted.length - 1))];
        return Math.round(nanos / 100_000.0) / 10.0; // ms with one decimal
    }
}
