package com.tixrush.event;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TicketTypeRepository extends JpaRepository<TicketType, Long> {

    List<TicketType> findByEventIdOrderBySortOrderAsc(Long eventId);

    /** The ticket types of a whole list of events in ONE query (instead of one query per event). */
    List<TicketType> findByEventIdInOrderBySortOrderAsc(Collection<Long> eventIds);

    long countByEventId(Long eventId);

    @Modifying
    @Query("delete from TicketType t where t.eventId = :eventId")
    int deleteByEventId(@Param("eventId") Long eventId);

    /**
     * Stock for the Redis counter. Also checks the type belongs to THIS event, so nobody can
     * buy event A through a ticket type id of event B.
     */
    @Query(value = "select available_tickets from ticket_types where id = :id and event_id = :eventId", nativeQuery = true)
    Optional<Integer> findAvailable(@Param("id") Long id, @Param("eventId") Long eventId);

    // ---- Used by the RabbitMQ workers: atomic "x = x - n" in the database, like EventRepository ----

    @Modifying
    @Query(value = "update ticket_types set available_tickets = available_tickets - :qty where id = :id and available_tickets >= :qty",
            nativeQuery = true)
    int decrementAvailable(@Param("id") Long id, @Param("qty") int qty);

    @Modifying
    @Query(value = "update ticket_types set available_tickets = available_tickets + :qty where id = :id and available_tickets + :qty <= total_tickets",
            nativeQuery = true)
    int incrementAvailable(@Param("id") Long id, @Param("qty") int qty);
}
