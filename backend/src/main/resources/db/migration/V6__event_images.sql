-- Event pictures, uploaded by an admin.
-- image_key: a new random id on every upload; it goes into the picture's URL, so a new picture
-- gets a new URL and browsers never show an old cached copy. NULL = no picture (generated art is shown).
ALTER TABLE events ADD COLUMN image_key VARCHAR(36);

-- The picture itself lives in its own table, so loading the event list never drags the bytes along.
CREATE TABLE event_images (
    event_id     BIGINT      PRIMARY KEY REFERENCES events (id) ON DELETE CASCADE,
    content_type VARCHAR(20) NOT NULL,
    data         BYTEA       NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
