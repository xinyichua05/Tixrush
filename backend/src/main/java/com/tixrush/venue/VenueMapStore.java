package com.tixrush.venue;

import com.tixrush.event.EventImageStore.StoredImage;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Reads and writes the seating-plan picture bytes (venue_maps). Same pattern as EventImageStore. */
@Repository
public class VenueMapStore {

    private final JdbcClient jdbc;

    public VenueMapStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Insert, or replace the map if the venue already has one ("upsert"). */
    public void save(long venueId, String contentType, byte[] data) {
        jdbc.sql("""
                        insert into venue_maps (venue_id, content_type, data, updated_at)
                        values (:id, :type, :data, now())
                        on conflict (venue_id) do update
                           set content_type = excluded.content_type, data = excluded.data, updated_at = now()
                        """)
                .param("id", venueId).param("type", contentType).param("data", data)
                .update();
    }

    public Optional<StoredImage> find(long venueId) {
        return jdbc.sql("select content_type, data from venue_maps where venue_id = :id")
                .param("id", venueId)
                .query((rs, row) -> new StoredImage(rs.getString("content_type"), rs.getBytes("data")))
                .optional();
    }

    public void delete(long venueId) {
        jdbc.sql("delete from venue_maps where venue_id = :id").param("id", venueId).update();
    }
}
