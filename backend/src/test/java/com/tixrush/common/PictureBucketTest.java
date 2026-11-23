package com.tixrush.common;

import com.jayway.jsonpath.JsonPath;
import com.tixrush.TestcontainersConfiguration;
import com.tixrush.event.Event;
import com.tixrush.event.EventRepository;
import com.tixrush.venue.Venue;
import com.tixrush.venue.VenueRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * With a bucket configured (online), pictures go to object storage instead of Postgres, and the URLs
 * point at the storage's CDN. A fake in-memory bucket stands in for Supabase, so no network is used.
 * Not @Transactional on purpose: old files are deleted only AFTER the DB change commits.
 */
@Import({TestcontainersConfiguration.class, PictureBucketTest.FakeBucketConfig.class})
@SpringBootTest
@AutoConfigureMockMvc
class PictureBucketTest {

    private static final String CDN = "https://cdn.test/public/pictures/";

    /** The smallest valid PNG (same as EventImageTest). */
    private static final byte[] PNG = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0x0D, 'I', 'H', 'D', 'R', 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89,
            0, 0, 0, 0x0A, 'I', 'D', 'A', 'T', 0x78, (byte) 0x9C, 0x63, 0, 1, 0, 0, 5, 0, 1, 0x0D, 0x0A, 0x2D, (byte) 0xB4,
            0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

    /** An in-memory bucket: key -> bytes. */
    static class FakeBucket implements PictureStorage {
        final Map<String, byte[]> files = new ConcurrentHashMap<>();

        @Override
        public void put(String key, String contentType, byte[] data) {
            files.put(key, data);
        }

        @Override
        public void delete(String key) {
            files.remove(key);
        }

        @Override
        public String publicUrl(String key) {
            return CDN + key;
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeBucketConfig {
        @Bean
        FakeBucket fakeBucket() {
            return new FakeBucket();
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired EventRepository eventRepository;
    @Autowired VenueRepository venueRepository;
    @Autowired FakeBucket bucket;

    private Long eventId;
    private Long venueId;

    @BeforeEach
    void setUp() {
        bucket.files.clear();
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        eventId = eventRepository.saveAndFlush(new Event("Bucket Test", "Somewhere Hall, Ipoh",
                now.plus(30, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), 100)).getId();
        venueId = venueRepository.saveAndFlush(new Venue("Bucket Test Arena " + now.toEpochMilli())).getId();
    }

    @AfterEach
    void cleanUp() {
        eventRepository.findById(eventId).ifPresent(eventRepository::delete);
        venueRepository.findById(venueId).ifPresent(venueRepository::delete);
    }

    @Test
    void upload_storesTheFileInTheBucket_andTheUrlPointsAtTheCdn() throws Exception {
        String url = JsonPath.read(mockMvc.perform(upload("/api/events/" + eventId + "/image").with(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.imageUrl");

        assertThat(url).startsWith(CDN + "events/" + eventId + "/");
        String key = url.substring(CDN.length());
        assertThat(bucket.files).containsOnlyKeys(key);
        assertThat(bucket.files.get(key)).isEqualTo(PNG);
        mockMvc.perform(get("/api/events/" + eventId)).andExpect(jsonPath("$.imageUrl").value(url));
    }

    @Test
    void replacingAPicture_deletesTheOldFile_removingDeletesTheLastOne() throws Exception {
        String first = JsonPath.read(mockMvc.perform(upload("/api/events/" + eventId + "/image").with(admin()))
                .andReturn().getResponse().getContentAsString(), "$.imageUrl");
        String second = JsonPath.read(mockMvc.perform(upload("/api/events/" + eventId + "/image").with(admin()))
                .andReturn().getResponse().getContentAsString(), "$.imageUrl");

        assertThat(second).isNotEqualTo(first);
        assertThat(bucket.files).containsOnlyKeys(second.substring(CDN.length())); // the old one is gone

        mockMvc.perform(delete("/api/events/" + eventId + "/image").with(admin())).andExpect(status().isNoContent());
        assertThat(bucket.files).isEmpty();
        mockMvc.perform(get("/api/events/" + eventId)).andExpect(jsonPath("$.imageUrl").doesNotExist());
    }

    @Test
    void deletingTheEvent_alsoDeletesItsPoster() throws Exception {
        mockMvc.perform(upload("/api/events/" + eventId + "/image").with(admin())).andExpect(status().isOk());
        assertThat(bucket.files).hasSize(1);

        mockMvc.perform(delete("/api/events/" + eventId).with(admin())).andExpect(status().isNoContent());
        assertThat(bucket.files).isEmpty();
    }

    @Test
    void venueSeatingPlans_goToTheBucketToo() throws Exception {
        String url = JsonPath.read(mockMvc.perform(upload("/api/venues/" + venueId + "/map").with(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.mapUrl");

        assertThat(url).startsWith(CDN + "venues/" + venueId + "/");
        assertThat(bucket.files).containsOnlyKeys(url.substring(CDN.length()));
    }

    private MockMultipartHttpServletRequestBuilder upload(String path) {
        return (MockMultipartHttpServletRequestBuilder) multipart(path)
                .file(new MockMultipartFile("file", "photo.png", "image/png", PNG))
                .with(request -> {
                    request.setMethod("PUT"); // multipart() builds a POST; our endpoints are PUT
                    return request;
                });
    }

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
