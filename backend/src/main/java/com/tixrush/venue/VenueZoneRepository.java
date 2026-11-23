package com.tixrush.venue;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface VenueZoneRepository extends JpaRepository<VenueZone, Long> {

    List<VenueZone> findByVenueIdOrderBySortOrderAsc(Long venueId);

    /** The zones of many venues in ONE query (for the venue list). */
    List<VenueZone> findByVenueIdInOrderBySortOrderAsc(Collection<Long> venueIds);

    @Modifying
    @Query("delete from VenueZone z where z.venueId = :venueId")
    int deleteByVenueId(@Param("venueId") Long venueId);
}
