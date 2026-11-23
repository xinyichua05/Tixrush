package com.tixrush.common;

/** Login failed (wrong email or password). Becomes HTTP 401. */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
