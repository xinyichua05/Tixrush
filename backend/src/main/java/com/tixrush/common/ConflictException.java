package com.tixrush.common;

/** Something clashes with existing data (e.g. email already registered). Becomes HTTP 409. */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
