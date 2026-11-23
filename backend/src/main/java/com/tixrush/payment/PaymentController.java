package com.tixrush.payment;

import com.tixrush.order.OrderResponse;
import com.tixrush.order.OrderService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Paying for your own order with Stripe (logged in; you only ever reach your own orders).
 *   POST /api/orders/{reservationId}/checkout       -> {"url": "https://checkout.stripe.com/..."}  go there to pay
 *   POST /api/orders/{reservationId}/payment/check  -> the order, after asking Stripe "was it paid?"
 */
@RestController
@RequestMapping("/api/orders/{reservationId}")
public class PaymentController {

    public record CheckoutLink(String url) {
    }

    private final PaymentService paymentService;
    private final OrderService orderService;

    public PaymentController(PaymentService paymentService, OrderService orderService) {
        this.paymentService = paymentService;
        this.orderService = orderService;
    }

    @PostMapping("/checkout")
    public CheckoutLink checkout(@PathVariable UUID reservationId, @AuthenticationPrincipal Jwt jwt) {
        return new CheckoutLink(paymentService.startCheckout(reservationId, userId(jwt)));
    }

    @PostMapping("/payment/check")
    public OrderResponse check(@PathVariable UUID reservationId, @AuthenticationPrincipal Jwt jwt) {
        paymentService.checkMine(reservationId, userId(jwt));
        return orderService.myOrder(reservationId, userId(jwt));
    }

    private static Long userId(Jwt jwt) {
        return Long.valueOf(jwt.getSubject());
    }
}
