package com.tixrush.common;

/** The request makes no sense (e.g. unknown strategy name). Becomes HTTP 400. */
public class BadRequestException extends RuntimeException {
    public BadRequestException(String message) {
        super(message);
    }
}
