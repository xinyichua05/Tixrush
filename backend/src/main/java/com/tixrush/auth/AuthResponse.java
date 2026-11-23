package com.tixrush.auth;

import java.time.Instant;

/** What register/login return. The client sends "token" back as "Authorization: Bearer <token>". */
public record AuthResponse(String token, Instant expiresAt, String role) {
}
