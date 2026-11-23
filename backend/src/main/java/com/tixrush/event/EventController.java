package com.tixrush.event;

import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * Controller = the HTTP layer. It turns HTTP requests into method calls and return
 * values into JSON. Keep it thin: no business logic here.
 */
@RestController
@RequestMapping("/api/events")
public class EventController {

    private final EventService eventService;
    private final EventImageService imageService;

    public EventController(EventService eventService, EventImageService imageService) {
        this.eventService = eventService;
        this.imageService = imageService;
    }

    // GET /api/events -> 200 + JSON array
    @GetMapping
    public List<EventResponse> listEvents() {
        return eventService.listEvents();
    }

    // GET /api/events/42 -> 200, or 404 (NotFoundException -> GlobalExceptionHandler)
    // @PathVariable copies "{id}" from the URL into the parameter, converting "42" -> 42L.
    @GetMapping("/{id}")
    public EventResponse getEvent(@PathVariable Long id) {
        return eventService.getEvent(id);
    }

    // POST /api/events -> 201 Created + "Location: http://.../api/events/42"
    // @RequestBody: build the record from the JSON body.
    // @Valid: run the validation annotations; if any fail, Spring throws
    //         MethodArgumentNotValidException BEFORE this method runs, which becomes a 400.
    // ResponseEntity lets us control status + headers + body together.
    @PostMapping
    public ResponseEntity<EventResponse> createEvent(@Valid @RequestBody CreateEventRequest request) {
        EventResponse created = eventService.createEvent(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(created.id())
                .toUri();
        return ResponseEntity.created(location).body(created);
    }

    // PUT /api/events/42 -> 200 + updated event
    @PutMapping("/{id}")
    public EventResponse updateEvent(@PathVariable Long id, @Valid @RequestBody UpdateEventRequest request) {
        return eventService.updateEvent(id, request);
    }

    // DELETE /api/events/42 -> 204 No Content (success, nothing to send back)
    // When only the status code changes, @ResponseStatus is simpler than ResponseEntity.
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEvent(@PathVariable Long id) {
        eventService.deleteEvent(id);
    }

    // ---- Pictures ----

    // PUT /api/events/42/image  (admin; multipart form with a "file" field) -> 200 + updated event
    @PutMapping(value = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EventResponse uploadImage(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return imageService.upload(id, file);
    }

    // DELETE /api/events/42/image  (admin) -> 204
    @DeleteMapping("/{id}/image")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeImage(@PathVariable Long id) {
        imageService.remove(id);
    }

    /**
     * GET /api/events/42/image?v=...  (everyone) -> the picture bytes.
     * The URL contains the picture's key, and a new upload gets a new key. So this exact URL never
     * changes content, and browsers may keep it for a year without asking again ("immutable").
     * nosniff: the browser must trust our Content-Type, never guess it from the bytes.
     */
    @GetMapping("/{id}/image")
    public ResponseEntity<byte[]> getImage(@PathVariable Long id) {
        EventImageStore.StoredImage image = imageService.get(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(image.data());
    }
}
