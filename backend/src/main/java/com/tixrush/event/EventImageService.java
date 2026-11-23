package com.tixrush.event;

import com.tixrush.common.ImageFiles;
import com.tixrush.common.NotFoundException;
import com.tixrush.common.Pictures;
import com.tixrush.config.RedisConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Event pictures: upload (admin), remove (admin) and serve (everyone).
 * The checks (real picture? not too big?) live in ImageFiles, shared with venue seating plans.
 * Where the bytes go (object storage online, Postgres locally) is decided by Pictures.
 */
@Service
public class EventImageService {

    public static final int MAX_BYTES = ImageFiles.MAX_BYTES;

    private final EventRepository eventRepository;
    private final EventImageStore store;
    private final EventViews views;
    private final Pictures pictures;

    public EventImageService(EventRepository eventRepository, EventImageStore store, EventViews views, Pictures pictures) {
        this.eventRepository = eventRepository;
        this.store = store;
        this.views = views;
        this.pictures = pictures;
    }

    /**
     * Saves the picture and gives it a new key (so its URL changes).
     * @CacheEvict: the cached event JSON still has the old imageUrl, so drop it.
     */
    @CacheEvict(cacheNames = RedisConfig.EVENT_CACHE, key = "#id")
    @Transactional
    public EventResponse upload(Long id, MultipartFile file) {
        String oldKey = findOrThrow(id).getImageKey();
        ImageFiles.Checked image = ImageFiles.check(file);
        String newKey = UUID.randomUUID().toString();
        if (pictures.inBucket()) {
            pictures.upload(Pictures.eventObject(id, newKey), image);
            if (oldKey != null) {
                pictures.deleteAfterCommit(Pictures.eventObject(id, oldKey)); // the replaced picture
            }
        } else {
            store.save(id, image.contentType(), image.data());
        }
        eventRepository.setImageKey(id, newKey);
        return views.of(findOrThrow(id)); // re-read: the update above ran as plain SQL
    }

    @CacheEvict(cacheNames = RedisConfig.EVENT_CACHE, key = "#id")
    @Transactional
    public void remove(Long id) {
        String oldKey = findOrThrow(id).getImageKey();
        if (pictures.inBucket()) {
            if (oldKey != null) {
                pictures.deleteAfterCommit(Pictures.eventObject(id, oldKey));
            }
        } else {
            store.delete(id);
        }
        eventRepository.setImageKey(id, null);
    }

    @Transactional(readOnly = true)
    public EventImageStore.StoredImage get(Long id) {
        return store.find(id).orElseThrow(() -> new NotFoundException("This event has no picture."));
    }

    private Event findOrThrow(Long id) {
        return eventRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("We couldn't find that event."));
    }
}
