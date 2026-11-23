package com.tixrush.common;

/** Logged in, but not allowed to do this right now (e.g. buying without a waiting-room pass). Becomes HTTP 403. */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException(String message) {
        super(message);
    }
}
