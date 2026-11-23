package com.tixrush.event;

import com.tixrush.common.ImageFiles;
import com.tixrush.common.NotFoundException;
import com.tixrush.config.RedisConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Event pictures: upload (admin), remove (admin) and serve (everyone).
 * The checks (real picture? not too big?) live in ImageFiles, shared with venue seating plans.
 */
@Service
public class EventImageService {

    public static final int MAX_BYTES = ImageFiles.MAX_BYTES;

    private final EventRepository eventRepository;
    private final EventImageStore store;
    private final EventViews views;

    public EventImageService(EventRepository eventRepository, EventImageStore store, EventViews views) {
        this.eventRepository = eventRepository;
        this.store = store;
        this.views = views;
    }

    /**
     * Saves the picture and gives it a new key (so its URL changes).
     * @CacheEvict: the cached event JSON still has the old imageUrl, so drop it.
     */
    @CacheEvict(cacheNames = RedisConfig.EVENT_CACHE, key = "#id")
    @Transactional
    public EventResponse upload(Long id, MultipartFile file) {
        findOrThrow(id);
        ImageFiles.Checked image = ImageFiles.check(file);
        store.save(id, image.contentType(), image.data());
        eventRepository.setImageKey(id, UUID.randomUUID().toString());
        return views.of(findOrThrow(id)); // re-read: the update above ran as plain SQL
    }

    @CacheEvict(cacheNames = RedisConfig.EVENT_CACHE, key = "#id")
    @Transactional
    public void remove(Long id) {
        findOrThrow(id);
        store.delete(id);
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
