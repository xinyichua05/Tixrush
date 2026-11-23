package com.tixrush.common;

/**
 * Object storage for pictures (event posters, venue seating plans): a "bucket" of files on another
 * service, served to browsers straight from that service's CDN. Real sites keep files there instead
 * of in the database: the DB stays small, and the app server doesn't spend its time sending pictures.
 *
 * Only exists when a bucket is configured (see S3PictureStorage). Without one, pictures are kept in
 * Postgres as before (EventImageStore / VenueMapStore), which is what local development uses.
 */
public interface PictureStorage {

    /** Store (or overwrite) a file under this key, e.g. "events/42/3f2a...". */
    void put(String key, String contentType, byte[] data);

    /** Remove the file. Removing one that doesn't exist is fine. */
    void delete(String key);

    /** The address a browser can load the file from. */
    String publicUrl(String key);
}
