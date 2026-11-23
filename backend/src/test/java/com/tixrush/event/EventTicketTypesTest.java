package com.tixrush.event;

import com.jayway.jsonpath.JsonPath;
import com.tixrush.order.OrderRepository;
import com.tixrush.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Creating and editing events that sell ticket types: zones copied from a saved venue, or simple
 * hand-typed types (an exhibition). Real Postgres; @Transactional rolls everything back afterwards.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EventTicketTypesTest {

    @Autowired MockMvc mockMvc;
    @Autowired OrderRepository orderRepository;
    @Autowired UserRepository userRepository;

    private final String startsAt = Instant.now().plus(30, ChronoUnit.DAYS).toString();
    private final String saleStartsAt = Instant.now().plus(1, ChronoUnit.DAYS).toString();
    private Long venueId;
    private long rockZoneId;
    private long cat1Id;

    @BeforeEach
    void createVenue() throws Exception {
        String json = mockMvc.perform(post("/api/venues").with(admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Ticket Types Arena %s","zones":[
                                  {"name":"Rock Zone","kind":"STANDING","capacity":2000,"color":"#e11d48"},
                                  {"name":"CAT 1","kind":"SEATED","rows":30,"seatsPerRow":50,"color":"#2563eb"},
                                  {"name":"CAT 2","kind":"SEATED","rows":30,"seatsPerRow":30,"color":"#16a34a"}]}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        venueId = ((Number) JsonPath.read(json, "$.id")).longValue();
        rockZoneId = ((Number) JsonPath.read(json, "$.zones[0].id")).longValue();
        cat1Id = ((Number) JsonPath.read(json, "$.zones[1].id")).longValue();
    }

    @Test
    void fromVenue_copiesTheChosenZones_andAddsUpTheTickets() throws Exception {
        // Sent in the "wrong" order and without CAT 2: the event keeps the venue's zone order.
        String json = createEvent("""
                "venueId":%d,"ticketTypes":[
                  {"venueZoneId":%d,"price":688,"tickets":1000},
                  {"venueZoneId":%d,"price":488,"tickets":2000}]
                """.formatted(venueId, cat1Id, rockZoneId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.venue").value(containsString("Ticket Types Arena"))) // from the venue
                .andExpect(jsonPath("$.venueId").value(venueId))
                .andExpect(jsonPath("$.totalTickets").value(3500)) // CAT 1 sells EVERY seat (1500), whatever was sent
                .andExpect(jsonPath("$.availableTickets").value(3500))
                .andExpect(jsonPath("$.ticketTypes.length()").value(2))
                .andExpect(jsonPath("$.ticketTypes[0].name").value("Rock Zone"))
                .andExpect(jsonPath("$.ticketTypes[0].kind").value("STANDING"))
                .andExpect(jsonPath("$.ticketTypes[0].color").value("#e11d48"))
                .andExpect(jsonPath("$.ticketTypes[0].price").value(488))
                .andExpect(jsonPath("$.ticketTypes[1].name").value("CAT 1"))
                .andExpect(jsonPath("$.ticketTypes[1].availableTickets").value(1500))
                .andExpect(jsonPath("$.ticketTypes[1].seatRows").value(30))
                .andExpect(jsonPath("$.ticketTypes[1].seatsPerRow").value(50))
                .andExpect(jsonPath("$.ticketTypes[0].seatRows").value(nullValue())) // Rock Zone: standing
                .andExpect(jsonPath("$.minPrice").value(488))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get("/api/events/" + JsonPath.read(json, "$.id")))
                .andExpect(jsonPath("$.ticketTypes[1].name").value("CAT 1"));

        // Every seat exists: 30 rows (A..Z, then AA..AD) x 50 seats, front row first.
        Object eventId = JsonPath.read(json, "$.id");
        Object cat1 = JsonPath.read(json, "$.ticketTypes[1].id");
        mockMvc.perform(get("/api/events/" + eventId + "/ticket-types/" + cat1 + "/seats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("CAT 1"))
                .andExpect(jsonPath("$.rows.length()").value(30))
                .andExpect(jsonPath("$.rows[0].label").value("A"))
                .andExpect(jsonPath("$.rows[25].label").value("Z"))
                .andExpect(jsonPath("$.rows[26].label").value("AA"))
                .andExpect(jsonPath("$.rows[29].label").value("AD"))
                .andExpect(jsonPath("$.rows[0].seats.length()").value(50))
                .andExpect(jsonPath("$.rows[0].seats[0].number").value(1))
                .andExpect(jsonPath("$.rows[0].seats[49].number").value(50));
        // A standing zone has no seat map.
        Object rock = JsonPath.read(json, "$.ticketTypes[0].id");
        mockMvc.perform(get("/api/events/" + eventId + "/ticket-types/" + rock + "/seats")).andExpect(status().isNotFound());
    }

    @Test
    void simpleTickets_withoutAVenue_forAnExhibition() throws Exception {
        createEvent("""
                "ticketTypes":[
                  {"name":"Adult","price":25,"tickets":800},
                  {"name":"Student","price":15.50,"tickets":300},
                  {"name":"Child","price":0,"tickets":200}]
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.venue").value("Somewhere Gallery, KL"))
                .andExpect(jsonPath("$.venueId").value(nullValue()))
                .andExpect(jsonPath("$.mapUrl").value(nullValue()))
                .andExpect(jsonPath("$.totalTickets").value(1300))
                .andExpect(jsonPath("$.ticketTypes[1].name").value("Student"))
                .andExpect(jsonPath("$.ticketTypes[1].price").value(15.5))
                .andExpect(jsonPath("$.ticketTypes[1].kind").value(nullValue()))
                .andExpect(jsonPath("$.ticketTypes[1].color").value(nullValue()))
                .andExpect(jsonPath("$.minPrice").value(0));
    }

    @Test
    void oldStyle_singlePool_stillWorks() throws Exception {
        createEvent("\"totalTickets\":500")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalTickets").value(500))
                .andExpect(jsonPath("$.ticketTypes.length()").value(0))
                .andExpect(jsonPath("$.minPrice").value(nullValue()));
    }

    @Test
    void badTicketTypes_areRefused() throws Exception {
        // A zone id that belongs to no zone of this venue.
        createEvent("\"venueId\":%d,\"ticketTypes\":[{\"venueZoneId\":%d,\"price\":1,\"tickets\":1}]".formatted(venueId, 999_999_999L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("isn't part of")));
        // A venue but no zones chosen.
        createEvent("\"venueId\":%d".formatted(venueId))
                .andExpect(status().isBadRequest());
        // Simple tickets: missing and duplicate names.
        createEvent("\"ticketTypes\":[{\"price\":1,\"tickets\":1}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("needs a name")));
        createEvent("\"ticketTypes\":[{\"name\":\"Adult\",\"price\":1,\"tickets\":1},{\"name\":\"adult \",\"price\":2,\"tickets\":1}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("both called")));
        // Field rules.
        createEvent("\"ticketTypes\":[{\"name\":\"Adult\",\"price\":-1,\"tickets\":0}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['ticketTypes[0].price']").exists())
                .andExpect(jsonPath("$.errors['ticketTypes[0].tickets']").exists());
        // Neither ticket types nor a number of tickets.
        createEvent("\"venue2\":1").andExpect(status().isBadRequest());
    }

    @Test
    void afterTheFirstOrder_onlyPricesCanChange() throws Exception {
        String json = createEvent("\"ticketTypes\":[{\"name\":\"Adult\",\"price\":25,\"tickets\":100}]")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long eventId = ((Number) JsonPath.read(json, "$.id")).longValue();

        // Before any sale: the types can be replaced freely.
        json = update(eventId, "[{\"name\":\"Adult\",\"price\":25,\"tickets\":100},{\"name\":\"Student\",\"price\":15,\"tickets\":50}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTickets").value(150))
                .andReturn().getResponse().getContentAsString();
        long typeId = ((Number) JsonPath.read(json, "$.ticketTypes[0].id")).longValue();

        // Someone buys 2 Adult tickets.
        Long userId = userRepository.findByEmail("admin@tixrush.local").orElseThrow().getId();
        orderRepository.insertPendingTicketsIfAbsent(UUID.randomUUID(), eventId, userId, "queue",
                Instant.now().plus(10, ChronoUnit.MINUTES), Instant.now(), typeId, 2);

        // Now changing the numbers is refused...
        update(eventId, "[{\"name\":\"Adult\",\"price\":25,\"tickets\":999},{\"name\":\"Student\",\"price\":15,\"tickets\":50}]")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("Prices can")));
        // ...but a new price is fine.
        update(eventId, "[{\"name\":\"Adult\",\"price\":30,\"tickets\":100},{\"name\":\"Student\",\"price\":15,\"tickets\":50}]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketTypes[0].price").value(30))
                .andExpect(jsonPath("$.totalTickets").value(150));
    }

    private org.springframework.test.web.servlet.ResultActions createEvent(String extraJson) throws Exception {
        return mockMvc.perform(post("/api/events").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Ticket Types Test","venue":"Somewhere Gallery, KL",
                         "startsAt":"%s","saleStartsAt":"%s",%s}
                        """.formatted(startsAt, saleStartsAt, extraJson)));
    }

    private org.springframework.test.web.servlet.ResultActions update(long eventId, String ticketTypesJson) throws Exception {
        return mockMvc.perform(put("/api/events/" + eventId).with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Ticket Types Test","venue":"Somewhere Gallery, KL",
                         "startsAt":"%s","saleStartsAt":"%s","ticketTypes":%s}
                        """.formatted(startsAt, saleStartsAt, ticketTypesJson)));
    }

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }
}
