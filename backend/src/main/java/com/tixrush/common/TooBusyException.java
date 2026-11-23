package com.tixrush.common;

/** Temporarily overloaded; the client should retry later. Becomes HTTP 503. */
public class TooBusyException extends RuntimeException {
    public TooBusyException(String message) {
        super(message);
    }
}
