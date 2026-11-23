package com.tixrush.order;

import org.springframework.beans.factory.annotation.Value;
import com.tixrush.waitingroom.WaitingRoomService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * POST /api/events/{eventId}/purchases   (?strategy=naive|pessimistic|optimistic|redis|queue: dev tools only)
 * Optional header  Idempotency-Key: <random id per click>
 * Optional body    {"ticketTypeId": 7, "quantity": 2}   for events that sell ticket types
 *                  {"ticketTypeId": 7, "seatIds": [301, 302]}   exact seats (numbered seats)
 *                  (always the production Redis + queue path; ?strategy is ignored then)
 *
 *   201 = you got a ticket (order saved)       202 = you got a ticket, order is being created (queue)
 *   409 = sold out                             429 = too many attempts (rate limit)
 *   503 = too busy / couldn't queue            401 = not logged in
 *   403 = this sale has a waiting room and it isn't your turn yet
 */
@RestController
@RequestMapping("/api/events/{eventId}/purchases")
public class PurchaseController {

    private static final String QUEUE = "queue";

    private final PurchaseService purchaseService;
    private final TicketTypePurchaseService ticketTypePurchase;
    private final RateLimiter rateLimiter;
    private final IdempotencyService idempotency;
    private final WaitingRoomService waitingRoom;
    private final boolean devTools;

    public PurchaseController(PurchaseService purchaseService, TicketTypePurchaseService ticketTypePurchase,
                              RateLimiter rateLimiter,
                              IdempotencyService idempotency, WaitingRoomService waitingRoom,
                              @Value("${tixrush.dev-tools.enabled:false}") boolean devTools) {
        this.purchaseService = purchaseService;
        this.ticketTypePurchase = ticketTypePurchase;
        this.rateLimiter = rateLimiter;
        this.idempotency = idempotency;
        this.waitingRoom = waitingRoom;
        this.devTools = devTools;
    }

    @PostMapping
    public ResponseEntity<OrderResponse> purchase(@PathVariable Long eventId,
                                                  @RequestParam(defaultValue = PurchaseService.DEFAULT_STRATEGY) String strategy,
                                                  @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                                  @RequestBody(required = false) PurchaseRequest choice,
                                                  // Spring Security already checked the token. This hands us its contents.
                                                  @AuthenticationPrincipal Jwt jwt) {
        Long userId = Long.valueOf(jwt.getSubject()); // "sub" = user id, set in JwtService
        // Customers always get the production path (Redis + queue). Choosing the older versions
        // (including the deliberately broken "naive") is a developer tool only.
        String chosen = devTools ? strategy : QUEUE;

        // A replayed request (same key) returns the stored answer and skips everything inside.
        IdempotencyService.Outcome outcome = idempotency.execute(userId, idempotencyKey, () -> {
            waitingRoom.checkPass(eventId, userId); // 403 if this sale has a line and it's not your turn
            rateLimiter.checkBuyAllowed(userId);   // 429 before we spend any DB/Redis work on buying
            OrderResponse order = choice != null && choice.ticketTypeId() != null
                    ? ticketTypePurchase.purchase(eventId, userId, choice.ticketTypeId(), choice.quantityOrOne(), choice.seatIds())
                    : purchaseService.purchase(chosen, eventId, userId);
            waitingRoom.passUsed(eventId, userId, order); // one pass = one order (1-4 tickets)
            return order;
        });

        OrderResponse body = outcome.response();
        HttpStatus status = OrderResponse.QUEUED.equals(body.status()) ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .header("Idempotent-Replayed", String.valueOf(outcome.replayed()))
                .body(body);
    }

    /**
     * The optional JSON body: which ticket type, and how many (1-4; missing = 1).
     * seatIds: exact seats for ticket types with numbered seats (missing = best available).
     */
    public record PurchaseRequest(Long ticketTypeId, Integer quantity, List<Long> seatIds) {
        int quantityOrOne() {
            if (seatIds != null && !seatIds.isEmpty()) {
                return seatIds.size();
            }
            return quantity == null ? 1 : quantity;
        }
    }
}
