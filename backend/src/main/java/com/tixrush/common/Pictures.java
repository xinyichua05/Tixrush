package com.tixrush.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Where event posters and seating plans live, and the URL a browser uses to load them.
 *
 *   Bucket on (STORAGE_BUCKET set, online):  files in object storage, URL = the storage's public CDN link
 *   Bucket off (local development, tests):    bytes in Postgres, URL = /api/events/{id}/image?v=...
 *
 * Every upload gets a new random key, so a new picture always has a new URL and nobody sees a stale copy.
 */
@Component
public class Pictures {

    private static final Logger log = LoggerFactory.getLogger(Pictures.class);

    private final PictureStorage storage; // null = bucket off

    public Pictures(ObjectProvider<PictureStorage> storage) {
        this.storage = storage.getIfAvailable();
    }

    public boolean inBucket() {
        return storage != null;
    }

    public static String eventObject(long eventId, String imageKey) {
        return "events/" + eventId + "/" + imageKey;
    }

    public static String venueMapObject(long venueId, String mapKey) {
        return "venues/" + venueId + "/" + mapKey;
    }

    /** null when the event has no picture (the site then draws a generated poster). */
    public String eventUrl(long eventId, String imageKey) {
        if (imageKey == null) {
            return null;
        }
        return inBucket() ? storage.publicUrl(eventObject(eventId, imageKey))
                : "/api/events/" + eventId + "/image?v=" + imageKey;
    }

    public String venueMapUrl(long venueId, String mapKey) {
        if (mapKey == null) {
            return null;
        }
        return inBucket() ? storage.publicUrl(venueMapObject(venueId, mapKey))
                : "/api/venues/" + venueId + "/map?v=" + mapKey;
    }

    /** Upload to the bucket now (before the DB change commits, so the new URL works straight away). */
    public void upload(String object, ImageFiles.Checked image) {
        storage.put(object, image.contentType(), image.data());
    }

    /**
     * Delete from the bucket, but only once the DB change is really saved: if the transaction rolls
     * back, the DB still points at this file, so it must stay. A failed delete only leaves an unused
     * file behind, so it's logged, not thrown.
     */
    public void deleteAfterCommit(String object) {
        if (!inBucket() || object == null) {
            return;
        }
        Runnable delete = () -> {
            try {
                storage.delete(object);
            } catch (RuntimeException e) {
                log.warn("Couldn't delete old picture {} from storage: {}", object, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete.run();
                }
            });
        } else {
            delete.run();
        }
    }
}
