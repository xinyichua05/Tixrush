package com.tixrush.auth;

import jakarta.validation.constraints.NotBlank;

/** JSON body of POST /api/auth/login. */
public record LoginRequest(@NotBlank String email, @NotBlank String password) {
}
