package com.tixrush.venue;

import org.springframework.context.annotation.Import;
import com.tixrush.TestcontainersConfiguration;

import com.jayway.jsonpath.JsonPath;
import com.tixrush.event.Event;
import com.tixrush.event.EventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Saved venues against the real Postgres: create/edit/delete (admin), read (everyone), the map picture. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class VenueApiTest {

    private static final byte[] PNG = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A,
            0, 0, 0, 0x0D, 'I', 'H', 'D', 'R', 0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89,
            0, 0, 0, 0x0A, 'I', 'D', 'A', 'T', 0x78, (byte) 0x9C, 0x63, 0, 1, 0, 0, 5, 0, 1, 0x0D, 0x0A, 0x2D, (byte) 0xB4,
            0, 0, 0, 0, 'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

    @Autowired MockMvc mockMvc;
    @Autowired EventRepository eventRepository;

    /** A unique name, so the test never clashes with venues already in the database. */
    private final String name = "Test Arena " + UUID.randomUUID().toString().substring(0, 8);

    private String body(String venueName, String zonesJson) {
        return "{\"name\":\"" + venueName + "\",\"zones\":" + zonesJson + "}";
    }

    private static final String ZONES = """
            [{"name":"Rock Zone","kind":"STANDING","capacity":2000,"color":"#E11D48"},
             {"name":"CAT 1","kind":"SEATED","rows":30,"seatsPerRow":50,"color":"#2563eb"}]
            """;

    private Long createVenue() throws Exception {
        String json = mockMvc.perform(post("/api/venues").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content(body(name, ZONES)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    @Test
    void admin_creates_everyoneReadsIt() throws Exception {
        Long id = createVenue();

        mockMvc.perform(get("/api/venues/" + id)) // no login needed
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(name))
                .andExpect(jsonPath("$.zones[0].name").value("Rock Zone"))
                .andExpect(jsonPath("$.zones[0].kind").value("STANDING"))
                .andExpect(jsonPath("$.zones[0].color").value("#e11d48")) // stored in lower case
                .andExpect(jsonPath("$.zones[1].name").value("CAT 1"))
                .andExpect(jsonPath("$.zones[1].rows").value(30))
                .andExpect(jsonPath("$.zones[1].seatsPerRow").value(50))
                .andExpect(jsonPath("$.zones[1].capacity").value(1500)) // rows x seats
                .andExpect(jsonPath("$.zones[0].rows").value(nullValue())) // standing: no seats
                .andExpect(jsonPath("$.totalCapacity").value(3500))
                .andExpect(jsonPath("$.eventCount").value(0));
        mockMvc.perform(get("/api/venues")).andExpect(jsonPath("$[*].name", hasItem(name)));
    }

    @Test
    void edit_replacesZones_andRenamesItsEvents() throws Exception {
        Long id = createVenue();
        Long eventId = createEventAt(id);

        mockMvc.perform(put("/api/venues/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(name + " Renamed", "[{\"name\":\"Floor\",\"kind\":\"STANDING\",\"capacity\":500,\"color\":\"#16a34a\"}]")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.zones.length()").value(1))
                .andExpect(jsonPath("$.zones[0].name").value("Floor"))
                .andExpect(jsonPath("$.eventCount").value(1));

        // The event shows the new venue name, but keeps the zones it copied (editing a venue never
        // changes what an event already sells).
        Event event = eventRepository.findById(eventId).orElseThrow();
        assertThat(event.getVenue()).isEqualTo(name + " Renamed");
        mockMvc.perform(get("/api/events/" + eventId))
                .andExpect(jsonPath("$.ticketTypes[0].name").value("Rock Zone"));
    }

    @Test
    void badInput_isRefused() throws Exception {
        String twice = "[{\"name\":\"VIP\",\"kind\":\"SEATED\",\"capacity\":10,\"color\":\"#000000\"},"
                + "{\"name\":\"vip\",\"kind\":\"SEATED\",\"capacity\":10,\"color\":\"#000000\"}]";
        mockMvc.perform(post("/api/venues").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body(name, twice)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("both called")));

        String badColour = "[{\"name\":\"VIP\",\"kind\":\"SEATED\",\"capacity\":10,\"color\":\"red\"}]";
        mockMvc.perform(post("/api/venues").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body(name, badColour)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['zones[0].color']").exists());

        mockMvc.perform(post("/api/venues").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body(name, "[]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.zones").exists());

        // A seated zone needs its seat grid; a standing zone needs a capacity.
        mockMvc.perform(post("/api/venues").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(name, "[{\"name\":\"VIP\",\"kind\":\"SEATED\",\"capacity\":10,\"color\":\"#000000\"}]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("rows and seats per row")));
        mockMvc.perform(post("/api/venues").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(name, "[{\"name\":\"Floor\",\"kind\":\"STANDING\",\"color\":\"#000000\"}]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("how many people fit")));
        mockMvc.perform(post("/api/venues").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content(body(name, "[{\"name\":\"VIP\",\"kind\":\"SEATED\",\"rows\":61,\"seatsPerRow\":10,\"color\":\"#000000\"}]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['zones[0].rows']").exists());

        createVenue();
        mockMvc.perform(post("/api/venues").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body(name.toUpperCase(), ZONES)))
                .andExpect(status().isConflict()); // same name, different case
    }

    @Test
    void onlyAdminsChangeVenues() throws Exception {
        mockMvc.perform(post("/api/venues").contentType(MediaType.APPLICATION_JSON).content(body(name, ZONES)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/venues").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON).content(body(name, ZONES)))
                .andExpect(status().isForbidden());
    }

    @Test
    void mapPicture_uploadedByAdmin_servedToEveryoneWithLongCache() throws Exception {
        Long id = createVenue();
        String json = mockMvc.perform(multipart("/api/venues/" + id + "/map")
                        .file(new MockMultipartFile("file", "plan.png", "image/png", PNG))
                        .with(r -> { r.setMethod("PUT"); return r; }).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mapUrl").value(startsWith("/api/venues/" + id + "/map?v=")))
                .andReturn().getResponse().getContentAsString();
        String mapUrl = JsonPath.read(json, "$.mapUrl");

        mockMvc.perform(get(mapUrl))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PNG))
                .andExpect(header().string("Cache-Control", containsString("immutable")));

        mockMvc.perform(delete("/api/venues/" + id + "/map").with(admin())).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/venues/" + id + "/map")).andExpect(status().isNotFound());
    }

    @Test
    void venueInUse_cantBeDeleted_unusedOneCan() throws Exception {
        Long id = createVenue();
        Long eventId = createEventAt(id);

        mockMvc.perform(delete("/api/venues/" + id).with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("used by 1 event")));

        mockMvc.perform(delete("/api/events/" + eventId).with(admin())).andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/venues/" + id).with(admin())).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/venues/" + id)).andExpect(status().isNotFound());
    }

    /** Creates an event through the API that sells the venue's first zone. Returns its id. */
    private Long createEventAt(Long venueId) throws Exception {
        String venue = mockMvc.perform(get("/api/venues/" + venueId)).andReturn().getResponse().getContentAsString();
        Number zoneId = JsonPath.read(venue, "$.zones[0].id");
        Instant now = Instant.now();
        String json = mockMvc.perform(post("/api/events").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Uses The Venue","venue":"ignored","startsAt":"%s","saleStartsAt":"%s",
                                 "venueId":%d,"ticketTypes":[{"venueZoneId":%d,"price":100,"tickets":50}]}
                                """.formatted(now.plus(30, ChronoUnit.DAYS), now.plus(1, ChronoUnit.DAYS), venueId, zoneId.longValue())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
