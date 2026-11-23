package com.tixrush.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** JSON body of POST /api/auth/register. */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 254) String email,
        // max 72: BCrypt only uses the first 72 bytes of a password.
        @NotBlank @Size(min = 8, max = 72) String password
) {
}
