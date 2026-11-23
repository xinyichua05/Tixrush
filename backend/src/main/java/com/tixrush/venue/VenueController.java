package com.tixrush.venue;

import com.tixrush.event.EventImageStore.StoredImage;
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

import java.time.Duration;
import java.util.List;

/**
 * /api/venues: reading is public (the event page shows the map), changing needs ADMIN
 * (see SecurityConfig).
 */
@RestController
@RequestMapping("/api/venues")
public class VenueController {

    private final VenueService venueService;

    public VenueController(VenueService venueService) {
        this.venueService = venueService;
    }

    @GetMapping
    public List<VenueResponse> list() {
        return venueService.list();
    }

    @GetMapping("/{id}")
    public VenueResponse get(@PathVariable Long id) {
        return venueService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public VenueResponse create(@Valid @RequestBody VenueRequest request) {
        return venueService.create(request);
    }

    @PutMapping("/{id}")
    public VenueResponse update(@PathVariable Long id, @Valid @RequestBody VenueRequest request) {
        return venueService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        venueService.delete(id);
    }

    @PutMapping(value = "/{id}/map", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public VenueResponse uploadMap(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return venueService.uploadMap(id, file);
    }

    @DeleteMapping("/{id}/map")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMap(@PathVariable Long id) {
        venueService.removeMap(id);
    }

    /** The seating-plan picture. Like event pictures: the ?v= key changes on upload, so cache it for a year. */
    @GetMapping("/{id}/map")
    public ResponseEntity<byte[]> getMap(@PathVariable Long id) {
        StoredImage map = venueService.getMap(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(map.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
                .header("X-Content-Type-Options", "nosniff")
                .body(map.data());
    }
}
