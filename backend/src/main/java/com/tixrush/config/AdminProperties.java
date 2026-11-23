package com.tixrush.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** The first admin account, created on startup by AdminSeeder. */
@ConfigurationProperties(prefix = "tixrush.admin")
public record AdminProperties(String email, String password) {
}
