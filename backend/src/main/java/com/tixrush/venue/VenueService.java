package com.tixrush.venue;

import com.tixrush.common.BadRequestException;
import com.tixrush.common.ConflictException;
import com.tixrush.common.ImageFiles;
import com.tixrush.common.NotFoundException;
import com.tixrush.event.EventImageStore.StoredImage;
import com.tixrush.event.EventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Saved venues: their name, seating-plan picture and zones. Admin edits, everyone reads. */
@Service
public class VenueService {

    private final VenueRepository venueRepository;
    private final VenueZoneRepository zoneRepository;
    private final VenueMapStore mapStore;
    private final EventRepository eventRepository;

    public VenueService(VenueRepository venueRepository, VenueZoneRepository zoneRepository,
                        VenueMapStore mapStore, EventRepository eventRepository) {
        this.venueRepository = venueRepository;
        this.zoneRepository = zoneRepository;
        this.mapStore = mapStore;
        this.eventRepository = eventRepository;
    }

    @Transactional(readOnly = true)
    public List<VenueResponse> list() {
        List<Venue> venues = venueRepository.findAllByOrderByNameAsc();
        // Two queries in total (zones of ALL venues at once), not one per venue.
        Map<Long, List<VenueZone>> zones = zoneRepository
                .findByVenueIdInOrderBySortOrderAsc(venues.stream().map(Venue::getId).toList()).stream()
                .collect(Collectors.groupingBy(VenueZone::getVenueId));
        return venues.stream()
                .map(v -> VenueResponse.from(v, zones.getOrDefault(v.getId(), List.of()),
                        eventRepository.countByVenueId(v.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public VenueResponse get(Long id) {
        return toResponse(findOrThrow(id));
    }

    @Transactional
    public VenueResponse create(VenueRequest request) {
        String name = request.name().trim();
        checkNameFree(name, null);
        checkZoneNamesUnique(request.zones());
        checkZoneSizes(request.zones());
        Venue venue = venueRepository.save(new Venue(name));
        saveZones(venue.getId(), request.zones());
        return toResponse(venue);
    }

    /**
     * Replaces the name and all zones. Events that already use this venue keep the zones they
     * copied when they were created, so their prices and stock are not touched.
     */
    @Transactional
    public VenueResponse update(Long id, VenueRequest request) {
        Venue venue = findOrThrow(id);
        String name = request.name().trim();
        checkNameFree(name, id);
        checkZoneNamesUnique(request.zones());
        checkZoneSizes(request.zones());
        if (!venue.getName().equals(name)) {
            venue.rename(name);
            eventRepository.renameVenue(id, name); // events show the venue's name: keep them in step
        }
        zoneRepository.deleteByVenueId(id);
        saveZones(id, request.zones());
        return toResponse(venue);
    }

    @Transactional
    public void delete(Long id) {
        Venue venue = findOrThrow(id);
        long used = eventRepository.countByVenueId(id);
        if (used > 0) {
            throw new ConflictException("This venue is used by " + used + (used == 1 ? " event" : " events")
                    + ". Change or delete those events first.");
        }
        venueRepository.delete(venue); // zones and map go too (ON DELETE CASCADE)
    }

    // ---- Seating-plan picture (same rules as event pictures) ----

    @Transactional
    public VenueResponse uploadMap(Long id, MultipartFile file) {
        findOrThrow(id);
        ImageFiles.Checked image = ImageFiles.check(file);
        mapStore.save(id, image.contentType(), image.data());
        venueRepository.setMapKey(id, UUID.randomUUID().toString()); // new key = new URL
        return toResponse(findOrThrow(id)); // re-read: setMapKey ran as plain SQL
    }

    @Transactional
    public void removeMap(Long id) {
        findOrThrow(id);
        mapStore.delete(id);
        venueRepository.setMapKey(id, null);
    }

    @Transactional(readOnly = true)
    public StoredImage getMap(Long id) {
        return mapStore.find(id).orElseThrow(() -> new NotFoundException("This venue has no seating plan."));
    }

    // ---- helpers ----

    private void saveZones(Long venueId, List<VenueRequest.Zone> zones) {
        for (int i = 0; i < zones.size(); i++) {
            VenueRequest.Zone z = zones.get(i);
            boolean seated = VenueZone.SEATED.equals(z.kind());
            int capacity = seated ? z.rows() * z.seatsPerRow() : z.capacity();
            zoneRepository.save(new VenueZone(venueId, z.name().trim(), z.kind(), capacity, z.color().toLowerCase(), i,
                    seated ? z.rows() : null, seated ? z.seatsPerRow() : null));
        }
    }

    /** Seated zones need their seat grid; standing zones need a capacity. */
    private static void checkZoneSizes(List<VenueRequest.Zone> zones) {
        for (VenueRequest.Zone z : zones) {
            if (VenueZone.SEATED.equals(z.kind()) && (z.rows() == null || z.seatsPerRow() == null)) {
                throw new BadRequestException("\"" + z.name().trim() + "\" is seated: give its number of rows and seats per row.");
            }
            if (VenueZone.STANDING.equals(z.kind()) && z.capacity() == null) {
                throw new BadRequestException("\"" + z.name().trim() + "\" is standing: give how many people fit.");
            }
        }
    }

    private void checkNameFree(String name, Long exceptId) {
        venueRepository.findByNameIgnoreCase(name)
                .filter(other -> !other.getId().equals(exceptId))
                .ifPresent(other -> {
                    throw new ConflictException("There is already a venue called \"" + other.getName() + "\".");
                });
    }

    private static void checkZoneNamesUnique(List<VenueRequest.Zone> zones) {
        Set<String> seen = new HashSet<>();
        for (VenueRequest.Zone z : zones) {
            if (!seen.add(z.name().trim().toLowerCase())) {
                throw new BadRequestException("Two zones are both called \"" + z.name().trim() + "\". Zone names must be different.");
            }
        }
    }

    private VenueResponse toResponse(Venue venue) {
        return VenueResponse.from(venue, zoneRepository.findByVenueIdOrderBySortOrderAsc(venue.getId()),
                eventRepository.countByVenueId(venue.getId()));
    }

    private Venue findOrThrow(Long id) {
        return venueRepository.findById(id).orElseThrow(() -> new NotFoundException("We couldn't find that venue."));
    }
}
