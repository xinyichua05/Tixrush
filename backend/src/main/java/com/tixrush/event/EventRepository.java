package com.tixrush.event;

import java.util.List;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Just an interface, with no implementation. Spring Data generates the class at startup.
 * You get save(), findById(), findAll(), deleteById(), existsById(), count() ... for free.
 */
public interface EventRepository extends JpaRepository<Event, Long> {

    /**
     * PESSIMISTIC lock: generates  SELECT ... FROM events WHERE id = ? FOR UPDATE
     * The row stays locked until the transaction ends. Any other transaction that asks
     * for the same lock WAITS in line, like a single cashier.
     */
    /** The real shop: everything except Practice rush events. */
    List<Event> findByPracticeFalse();

    List<Event> findByPracticeTrue();

    /** Used by the Practice rush to find its own event again. */
    Optional<Event> findFirstByNameOrderByIdAsc(String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Event e where e.id = :id")
    Optional<Event> findByIdForUpdate(@Param("id") Long id);

    /** Sets (or clears, with null) the picture key. clearAutomatically: forget cached entities so a re-read sees it. */
    @Modifying(clearAutomatically = true)
    @Query(value = "update events set image_key = :key where id = :id", nativeQuery = true)
    int setImageKey(@Param("id") Long id, @Param("key") String key);

    // ---- Venues ----

    long countByVenueId(Long venueId);

    /**
     * A venue was renamed: events held there show the new name too.
     * flush first (write pending changes, like the venue's own rename), then clear, so entities
     * loaded later in the same transaction see the new name instead of a stale copy.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update events set venue = :name where venue_id = :venueId", nativeQuery = true)
    int renameVenue(@Param("venueId") Long venueId, @Param("name") String name);

    // ---- Used by the RabbitMQ consumers (v4 complete) ----

    /** Same as decrementAvailable, for an order of several tickets (ticket types allow 1-4). */
    @Modifying
    @Query(value = "update events set available_tickets = available_tickets - :qty where id = :id and available_tickets >= :qty",
            nativeQuery = true)
    int decrementAvailableBy(@Param("id") Long id, @Param("qty") int qty);

    @Modifying
    @Query(value = "update events set available_tickets = available_tickets + :qty where id = :id and available_tickets + :qty <= total_tickets",
            nativeQuery = true)
    int incrementAvailableBy(@Param("id") Long id, @Param("qty") int qty);

    /**
     * Atomic "minus one" done by the DATABASE ("x = x - 1"), not read-in-Java-then-write.
     * Safe under concurrency, unlike the naive version. Runs in the background worker, so
     * buyers never wait for this hot row.
     */
    @Modifying
    @Query(value = "update events set available_tickets = available_tickets - 1 where id = :id and available_tickets > 0",
            nativeQuery = true)
    int decrementAvailable(@Param("id") Long id);

    /** Gives a ticket back (an unpaid order expired). */
    @Modifying
    @Query(value = "update events set available_tickets = available_tickets + 1 where id = :id and available_tickets < total_tickets",
            nativeQuery = true)
    int incrementAvailable(@Param("id") Long id);

    // ---- The two queries below exist ONLY for the deliberately broken naive version ----

    /** Plain read of the stock number. No lock. */
    @Query(value = "select available_tickets from events where id = :id", nativeQuery = true)
    Optional<Integer> findAvailableTickets(@Param("id") Long id);

    /**
     * Writes a stock number that was calculated EARLIER in Java from a value that may
     * already be out of date. That's the bug.
     * (The correct SQL would be "available_tickets = available_tickets - 1 WHERE available_tickets > 0".)
     */
    @Modifying
    @Query(value = "update events set available_tickets = :value where id = :id", nativeQuery = true)
    int setAvailableTickets(@Param("id") Long id, @Param("value") int value);
}
