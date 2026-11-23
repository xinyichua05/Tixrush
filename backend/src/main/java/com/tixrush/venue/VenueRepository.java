package com.tixrush.venue;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface VenueRepository extends JpaRepository<Venue, Long> {

    List<Venue> findAllByOrderByNameAsc();

    Optional<Venue> findByNameIgnoreCase(String name);

    /** Sets (or clears, with null) the seating-plan key. Same idea as EventRepository.setImageKey. */
    @Modifying(clearAutomatically = true)
    @Query(value = "update venues set map_key = :key where id = :id", nativeQuery = true)
    int setMapKey(@Param("id") Long id, @Param("key") String key);
}
