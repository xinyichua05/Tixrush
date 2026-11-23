package com.tixrush.event;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Reads and writes the picture bytes in the event_images table. Plain SQL through JdbcClient:
 * for "a blob of bytes by id" that's simpler than a JPA entity.
 */
@Repository
public class EventImageStore {

    /** A stored picture: its MIME type (e.g. "image/webp") and the bytes. */
    public record StoredImage(String contentType, byte[] data) {
    }

    private final JdbcClient jdbc;

    public EventImageStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert, or replace the picture if the event already has one ("upsert"). */
    public void save(long eventId, String contentType, byte[] data) {
        jdbc.sql("""
                        insert into event_images (event_id, content_type, data, updated_at)
                        values (:id, :type, :data, now())
                        on conflict (event_id) do update
                           set content_type = excluded.content_type, data = excluded.data, updated_at = now()
                        """)
                .param("id", eventId).param("type", contentType).param("data", data)
                .update();
    }

    public Optional<StoredImage> find(long eventId) {
        return jdbc.sql("select content_type, data from event_images where event_id = :id")
                .param("id", eventId)
                .query((rs, row) -> new StoredImage(rs.getString("content_type"), rs.getBytes("data")))
                .optional();
    }

    public void delete(long eventId) {
        jdbc.sql("delete from event_images where event_id = :id").param("id", eventId).update();
    }
}
