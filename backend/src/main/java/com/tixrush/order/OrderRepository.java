package com.tixrush.order;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, Long> {

    long countByEventId(Long eventId);

    long countByEventIdAndStatus(Long eventId, String status);

    long countByEventIdAndStatusIn(Long eventId, Collection<String> statuses);

    /** Tickets (not orders: one order can hold 1-4) that are still held: pass Order.RELEASED. */
    @Query("select coalesce(sum(o.quantity), 0) from Order o where o.eventId = :eventId and o.status not in :statuses")
    long sumQuantityByEventIdAndStatusNotIn(@Param("eventId") Long eventId, @Param("statuses") Collection<String> statuses);

    /** Orders that still hold a ticket: pass Order.RELEASED (everything except EXPIRED / CANCELLED). */
    long countByEventIdAndStatusNotIn(Long eventId, Collection<String> statuses);

    Optional<Order> findByReservationIdAndUserId(UUID reservationId, Long userId);

    Optional<Order> findByReservationId(UUID reservationId);

    List<Order> findTop20ByUserIdOrderByIdDesc(Long userId);

    /** "My tickets": your newest orders, without Practice rush tickets (they aren't real). */
    @Query("""
            select o from Order o
            where o.userId = :userId
              and o.eventId not in (select e.id from Event e where e.practice = true)
            order by o.id desc
            """)
    List<Order> findMine(@Param("userId") Long userId, Pageable pageable);

    List<Order> findByReservationIdIn(Collection<UUID> reservationIds);

    List<Order> findByEventId(Long eventId);

    /** One bulk DELETE statement (a derived deleteBy... would load and delete rows one by one). */
    @Modifying
    @Query("delete from Order o where o.eventId = :eventId")
    int deleteByEventId(@Param("eventId") Long eventId);

    /**
     * Used by the RabbitMQ consumer. Returns 1 if inserted, 0 if this reservation already exists.
     * RabbitMQ delivers "at least once", so the same message CAN arrive twice. ON CONFLICT DO NOTHING
     * on the UNIQUE reservation_id makes the second delivery harmless (= an idempotent consumer).
     *
     * Stale-order guard: a Practice rush can be stopped and restarted at once. An order from the OLD run
     * that is still in the queue was requested before the new run's sale started, so it is dropped
     * (0 rows) instead of taking a seat in the new run. Real events (practice = false) always insert.
     *
     * "for no key update" locks the event row while we check it, so the check can't race with a restart
     * that is moving the sale start (it waits, then re-checks the new value). It's the same lock the
     * worker takes a moment later anyway for "available_tickets - 1", so it adds no extra waiting.
     */
    @Modifying
    @Query(value = """
            insert into orders (event_id, user_id, strategy, status, reservation_id, expires_at)
            select :eventId, :userId, :strategy, 'PENDING_PAYMENT', :reservationId, :expiresAt
            where exists (select 1 from events e
                          where e.id = :eventId
                            and (e.practice = false or e.sale_starts_at <= :requestedAt)
                          for no key update)
            on conflict (reservation_id) do nothing
            """, nativeQuery = true)
    int insertPendingIfAbsent(@Param("reservationId") UUID reservationId, @Param("eventId") Long eventId,
                              @Param("userId") Long userId, @Param("strategy") String strategy,
                              @Param("expiresAt") Instant expiresAt, @Param("requestedAt") Instant requestedAt);

    /**
     * Same as insertPendingIfAbsent, for an order of 1-4 tickets of one ticket type. The price is copied
     * from ticket_types in the same statement, so the order keeps it even if the admin changes it later.
     * The join on t.event_id also refuses a ticket type that belongs to another event (0 rows).
     */
    @Modifying
    @Query(value = """
            insert into orders (event_id, user_id, strategy, status, reservation_id, expires_at,
                                ticket_type_id, quantity, unit_price)
            select :eventId, :userId, :strategy, 'PENDING_PAYMENT', :reservationId, :expiresAt,
                   t.id, :quantity, t.price
            from ticket_types t
            where t.id = :ticketTypeId and t.event_id = :eventId
              and exists (select 1 from events e
                          where e.id = :eventId
                            and (e.practice = false or e.sale_starts_at <= :requestedAt)
                          for no key update)
            on conflict (reservation_id) do nothing
            """, nativeQuery = true)
    int insertPendingTicketsIfAbsent(@Param("reservationId") UUID reservationId, @Param("eventId") Long eventId,
                                     @Param("userId") Long userId, @Param("strategy") String strategy,
                                     @Param("expiresAt") Instant expiresAt, @Param("requestedAt") Instant requestedAt,
                                     @Param("ticketTypeId") Long ticketTypeId, @Param("quantity") int quantity);

    /**
     * "Expire it, but ONLY if it's still unpaid". Returns 1 if this call expired it.
     * Paying uses the same trick, so pay and expire can never BOTH win: the row can only
     * leave PENDING_PAYMENT once.
     */
    @Modifying
    @Query("update Order o set o.status = 'EXPIRED' where o.reservationId = :id and o.status = 'PENDING_PAYMENT'")
    int expireIfPending(@Param("id") UUID reservationId);

    // clearAutomatically: drop any copy of the order already loaded in this transaction, so reading it
    // again after this UPDATE shows the new status (not the stale "PENDING_PAYMENT" from before).
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Order o set o.status = 'PAID', o.paidAt = :now
            where o.reservationId = :id and o.userId = :userId
              and o.status = 'PENDING_PAYMENT' and o.expiresAt > :now
            """)
    int payIfPending(@Param("id") UUID reservationId, @Param("userId") Long userId, @Param("now") Instant now);

    /** The buyer gives the tickets back. Same trick again: pay, expire and cancel can't both win. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Order o set o.status = 'CANCELLED'
            where o.reservationId = :id and o.userId = :userId and o.status = 'PENDING_PAYMENT'
            """)
    int cancelIfPending(@Param("id") UUID reservationId, @Param("userId") Long userId);
}
