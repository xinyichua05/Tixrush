package com.tixrush.event;

import com.tixrush.common.ConflictException;

/** No tickets left. Extends ConflictException, so it becomes HTTP 409. */
public class SoldOutException extends ConflictException {
    public SoldOutException(Long eventId) {
        this(eventId, "Sorry, this show is sold out.");
    }

    public SoldOutException(Long eventId, String message) {
        super(message);
    }
}
