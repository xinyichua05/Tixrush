package com.tixrush.common;

/** Throw this from any service when something doesn't exist. It becomes HTTP 404. */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
