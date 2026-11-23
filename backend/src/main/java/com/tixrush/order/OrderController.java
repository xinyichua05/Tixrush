package com.tixrush.order;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Your own orders (any logged-in user; you only ever see your own).
 *   GET  /api/orders/mine                       -> your latest 20 orders
 *   GET  /api/orders/{reservationId}            -> one order (404 = unknown or still processing)
 *   POST /api/orders/{reservationId}/pay        -> 200 PAID, or 409 if expired / already paid
 *   POST /api/orders/{reservationId}/cancel     -> 200 CANCELLED (tickets back on sale), or 409 if not unpaid
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/mine")
    public List<OrderResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return orderService.myOrders(userId(jwt));
    }

    @GetMapping("/{reservationId}")
    public OrderResponse one(@PathVariable UUID reservationId, @AuthenticationPrincipal Jwt jwt) {
        return orderService.myOrder(reservationId, userId(jwt));
    }

    @PostMapping("/{reservationId}/pay")
    public OrderResponse pay(@PathVariable UUID reservationId, @AuthenticationPrincipal Jwt jwt) {
        return orderService.pay(reservationId, userId(jwt));
    }

    @PostMapping("/{reservationId}/cancel")
    public OrderResponse cancel(@PathVariable UUID reservationId, @AuthenticationPrincipal Jwt jwt) {
        return orderService.cancel(reservationId, userId(jwt));
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
