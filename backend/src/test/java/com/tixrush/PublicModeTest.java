package com.tixrush;

import org.springframework.context.annotation.Import;

import com.jayway.jsonpath.JsonPath;
import com.tixrush.event.Event;
import com.tixrush.event.EventRepository;
import com.tixrush.order.Order;
import com.tixrush.order.OrderRepository;
import com.tixrush.order.RedisStockService;
import com.tixrush.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real site (dev tools OFF, the default): no developer pages, no choosing the buy version,
 * and Practice rush events/tickets never show up in the real shop.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PublicModeTest {

    @Autowired MockMvc mockMvc;
    @Autowired EventRepository eventRepository;
    @Autowired OrderRepository orderRepository;
    @Autowired UserRepository userRepository;
    @Autowired RedisStockService redisStock;

    private Long realEventId;
    private Long practiceEventId;

    @BeforeEach
    void createEvents() {
        Instant now = Instant.now();
        realEventId = eventRepository.save(new Event("Public Mode Real " + UUID.randomUUID(), "Test Hall",
                now.plus(30, ChronoUnit.DAYS), now.minus(1, ChronoUnit.HOURS), 10)).getId();
        Event practice = new Event("Public Mode Practice " + UUID.randomUUID(), "Test Hall",
                now.plus(30, ChronoUnit.DAYS), now.minus(1, ChronoUnit.HOURS), 10);
        practice.markPractice();
        practiceEventId = eventRepository.save(practice).getId();
    }

    @AfterEach
    void cleanUp() {
        redisStock.clear(realEventId);
        eventRepository.deleteAllById(List.of(realEventId, practiceEventId)); // orders go too (cascade)
    }

    @Test
    void developerPagesAndEndpoints_areHidden() throws Exception {
        String token = adminToken();
        mockMvc.perform(get("/visualizer/")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/visualizer/app.js")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/rush").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/admin/events/" + realEventId + "/stats").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void practiceEvents_andTheirTickets_stayOutOfTheShop() throws Exception {
        String token = adminToken();
        Long adminId = userRepository.findByEmail("admin@tixrush.local").orElseThrow().getId();
        orderRepository.save(new Order(realEventId, adminId, "queue"));
        orderRepository.save(new Order(practiceEventId, adminId, "queue"));

        String events = mockMvc.perform(get("/api/events")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Integer> eventIds = JsonPath.read(events, "$[*].id");
        assertThat(eventIds).contains(realEventId.intValue()).doesNotContain(practiceEventId.intValue());

        String mine = mockMvc.perform(get("/api/orders/mine").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Integer> orderEvents = JsonPath.read(mine, "$[*].eventId");
        assertThat(orderEvents).contains(realEventId.intValue()).doesNotContain(practiceEventId.intValue());
    }

    @Test
    @RequiresServices(rabbit = true)
    void askingForTheBrokenVersion_stillUsesTheSafeOne() throws Exception {
        mockMvc.perform(post("/api/events/" + realEventId + "/purchases?strategy=naive")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.strategy").value("queue"));
        // Let the background worker write the order before cleanUp() deletes the event.
        long deadline = System.currentTimeMillis() + 15_000;
        while (orderRepository.countByEventId(realEventId) == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
        }
        assertThat(orderRepository.countByEventId(realEventId)).isEqualTo(1);
    }

    private String adminToken() throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@tixrush.local\",\"password\":\"admin12345\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.token");
    }
}
