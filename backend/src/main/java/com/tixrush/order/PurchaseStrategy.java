package com.tixrush.order;

/**
 * One way of buying a ticket. Each version of "Buy" implements this interface.
 *
 * This is the "Strategy pattern": PurchaseService doesn't care HOW a ticket is reserved,
 * only that every strategy has the same method. Adding version 4 (Redis) later
 * means adding one new class, without touching the others.
 */
public interface PurchaseStrategy {

    /** Short name used in the URL: ?strategy=naive */
    String name();

    /** Reserve one ticket for this user, or throw SoldOutException. */
    OrderResponse purchase(Long eventId, Long userId);
}
