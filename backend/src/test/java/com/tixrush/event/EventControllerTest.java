package com.tixrush.event;

import com.tixrush.common.NotFoundException;
import com.tixrush.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A "slice" test: @WebMvcTest starts ONLY the web layer (this controller, JSON conversion,
 * validation, @RestControllerAdvice). No database, no real service, so it's fast.
 *
 * MockMvc sends fake HTTP requests straight into Spring MVC, without opening a real port.
 * @MockitoBean puts a fake EventService into Spring, and each test scripts what it returns.
 *
 * @Import(SecurityConfig.class) applies OUR security rules. jwt() pretends the request
 * carries a valid token with the given role, so we don't need to create real tokens here.
 */
@WebMvcTest(EventController.class)
@Import(SecurityConfig.class)
class EventControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    EventService eventService;

    @MockitoBean
    EventImageService imageService; // the controller needs one; picture endpoints are tested in EventImageTest

    private static final EventResponse SAMPLE = new EventResponse(
            7L, "Rush Hour Live", "Bukit Jalil",
            Instant.parse("2030-03-20T12:00:00Z"), Instant.parse("2030-01-10T02:00:00Z"),
            5000, 5000, null, null, null, List.of(), null);

    private static final String VALID_BODY = """
            {
              "name": "Rush Hour Live",
              "venue": "Bukit Jalil",
              "startsAt": "2030-03-20T12:00:00Z",
              "saleStartsAt": "2030-01-10T02:00:00Z",
              "totalTickets": 5000
            }
            """;

    private static RequestPostProcessor admin() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor user() {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    // ---------- reading is public ----------

    @Test
    void listEvents_noToken_returnsJsonArray() throws Exception {
        when(eventService.listEvents()).thenReturn(List.of(SAMPLE));

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].name").value("Rush Hour Live"))
                .andExpect(jsonPath("$[0].startsAt").value("2030-03-20T12:00:00Z"));
    }

    @Test
    void getEvent_unknownId_returns404ProblemDetail() throws Exception {
        when(eventService.getEvent(99L)).thenThrow(new NotFoundException("Event 99 not found"));

        mockMvc.perform(get("/api/events/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Event 99 not found"));
    }

    // ---------- writing needs ADMIN ----------

    @Test
    void createEvent_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/events").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isUnauthorized());
        verify(eventService, never()).createEvent(any());
    }

    @Test
    void createEvent_asUser_returns403() throws Exception {
        mockMvc.perform(post("/api/events").with(user()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isForbidden());
        verify(eventService, never()).createEvent(any());
    }

    @Test
    void createEvent_asAdmin_returns201WithLocation() throws Exception {
        when(eventService.createEvent(any())).thenReturn(SAMPLE);

        mockMvc.perform(post("/api/events").with(admin()).contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/events/7"))
                .andExpect(jsonPath("$.availableTickets").value(5000));
    }

    @Test
    void createEvent_invalidBody_returns400AndNeverCallsService() throws Exception {
        String body = """
                {
                  "name": "",
                  "venue": "Bukit Jalil",
                  "startsAt": "2030-01-01T00:00:00Z",
                  "saleStartsAt": "2030-06-01T00:00:00Z",
                  "totalTickets": -5
                }
                """;

        mockMvc.perform(post("/api/events").with(admin()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.totalTickets").exists())
                .andExpect(jsonPath("$.errors.saleBeforeShow").value("saleStartsAt must be before startsAt"));

        verify(eventService, never()).createEvent(any());
    }
}
