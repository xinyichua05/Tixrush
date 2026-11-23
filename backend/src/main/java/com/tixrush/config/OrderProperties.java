package com.tixrush.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/** tixrush.orders.payment-hold: how long a queued order waits for payment before the ticket goes back. */
@ConfigurationProperties("tixrush.orders")
public record OrderProperties(@DefaultValue("10m") Duration paymentHold) {
}
