package com.tixrush.event;

import org.springframework.context.annotation.Import;
import com.tixrush.TestcontainersConfiguration;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Event pictures, end to end against the real Postgres: upload (admin only), serve (everyone),
 * replace, remove, and the safety checks. @Transactional: everything is rolled back afterwards.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EventImageTest {

    /** The smallest valid PNG: the 8-byte PNG signature plus a 1x1 pixel. */
    private static final byte[] PNG = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0x0D, 'I', 'H', 'D', 'R', 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89,
            0, 0, 0, 0x0A, 'I', 'D', 'A', 'T', 0x78, (byte) 0x9C, 0x63, 0, 1, 0, 0, 5, 0, 1, 0x0D, 0x0A, 0x2D, (byte) 0xB4,
            0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'};

    @Autowired MockMvc mockMvc;
    @Autowired EventRepository eventRepository;

    private Long eventId;

    @BeforeEach
    void setUp() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        eventId = eventRepository.saveAndFlush(new Event("Picture Test", "Somewhere Hall, Ipoh",
                now.plus(30, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), 100)).getId();
    }

    @Test
    void admin_uploads_everyoneCanSeeIt_withALongCache() throws Exception {
        String body = mockMvc.perform(upload(PNG, "photo.png").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(startsWith("/api/events/" + eventId + "/image?v=")))
                .andReturn().getResponse().getContentAsString();
        String imageUrl = JsonPath.read(body, "$.imageUrl");

        // Anyone (no login) can load it: the same bytes, the right type, cached for a year.
        mockMvc.perform(get(imageUrl))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PNG))
                .andExpect(header().string("Cache-Control", containsString("immutable")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        // The event list shows it too.
        mockMvc.perform(get("/api/events/" + eventId)).andExpect(jsonPath("$.imageUrl").value(imageUrl));
    }

    @Test
    void anotherUpload_getsANewUrl_soOldCachedCopiesAreNeverShown() throws Exception {
        String first = JsonPath.read(mockMvc.perform(upload(PNG, "a.png").with(admin()))
                .andReturn().getResponse().getContentAsString(), "$.imageUrl");
        String second = JsonPath.read(mockMvc.perform(upload(JPEG, "b.jpg").with(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.imageUrl");

        assertThat(second).isNotEqualTo(first);
        mockMvc.perform(get(second)).andExpect(content().contentType("image/jpeg"));
    }

    @Test
    void onlyAdminsCanUpload() throws Exception {
        mockMvc.perform(upload(PNG, "photo.png")).andExpect(status().isUnauthorized());
        mockMvc.perform(upload(PNG, "photo.png").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void aFileThatIsNotReallyAPicture_isRefused_whateverItsNameSays() throws Exception {
        mockMvc.perform(upload("<script>alert(1)</script>".getBytes(), "photo.png").with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Please choose a JPG, PNG or WebP picture."));
        mockMvc.perform(upload(new byte[0], "empty.png").with(admin())).andExpect(status().isBadRequest());
    }

    @Test
    void aPictureOver2MB_isRefusedWith413() throws Exception {
        byte[] big = new byte[EventImageService.MAX_BYTES + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        mockMvc.perform(upload(big, "huge.png").with(admin()))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.detail").value("That picture is too big. Please use one under 2 MB."));
    }

    @Test
    void removingThePicture_bringsBackTheGeneratedArt() throws Exception {
        mockMvc.perform(upload(PNG, "photo.png").with(admin())).andExpect(status().isOk());

        mockMvc.perform(delete("/api/events/" + eventId + "/image").with(admin())).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/events/" + eventId)).andExpect(jsonPath("$.imageUrl").value(nullValue()));
        mockMvc.perform(get("/api/events/" + eventId + "/image")).andExpect(status().isNotFound());
    }

    @Test
    void uploadingForAMissingEvent_is404() throws Exception {
        mockMvc.perform(multipart("/api/events/999999999/image").file(new MockMultipartFile("file", "p.png", "image/png", PNG))
                        .with(put()).with(admin()))
                .andExpect(status().isNotFound());
    }

    private MockMultipartHttpServletRequestBuilder upload(byte[] bytes, String name) {
        return (MockMultipartHttpServletRequestBuilder) multipart("/api/events/" + eventId + "/image")
                .file(new MockMultipartFile("file", name, "image/png", bytes))
                .with(put());
    }

    /** multipart() builds a POST; our endpoint is PUT. */
    private static RequestPostProcessor put() {
        return request -> {
            request.setMethod("PUT");
            return request;
        };
    }

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
