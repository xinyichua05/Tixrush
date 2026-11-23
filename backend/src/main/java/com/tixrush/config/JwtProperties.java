package com.tixrush.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Type-safe settings: Spring copies "tixrush.jwt.secret" / "tixrush.jwt.ttl" from
 * application.yml (or env vars) into this record at startup.
 */
@ConfigurationProperties(prefix = "tixrush.jwt")
public record JwtProperties(String secret, Duration ttl) {

    public JwtProperties {
        // HS256 needs a key of at least 256 bits (32 bytes). Fail fast at startup if not.
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("tixrush.jwt.secret (JWT_SECRET) must be at least 32 bytes");
        }
        if (ttl == null) {
            ttl = Duration.ofHours(1);
        }
    }
}
