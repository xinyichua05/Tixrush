package com.tixrush.auth;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end test of the whole login flow with REAL tokens and the REAL database.
 * (Unlike EventControllerTest, nothing is faked here.)
 *
 * @Transactional on a test = everything it writes is rolled back afterwards, so the
 * database stays clean between runs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthFlowIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    private static final String EVENT_BODY = """
            {
              "name": "Integration Fest",
              "venue": "Axiata Arena",
              "startsAt": "2031-05-01T12:00:00Z",
              "saleStartsAt": "2031-04-01T02:00:00Z",
              "totalTickets": 100
            }
            """;

    @Test
    void userCanRegisterAndLogin_butCannotCreateEvents() throws Exception {
        String email = "fan-" + UUID.randomUUID() + "@example.com";
        String creds = "{\"email\":\"" + email + "\",\"password\":\"supersecret\"}";

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(creds))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("USER"));

        String token = login(creds);

        mockMvc.perform(post("/api/events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(EVENT_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    void seededAdminCanCreateEvents() throws Exception {
        String token = login("{\"email\":\"admin@tixrush.local\",\"password\":\"admin12345\"}");

        mockMvc.perform(post("/api/events").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(EVENT_BODY))
                .andExpect(status().isCreated());
    }

    @Test
    void wrongPassword_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@tixrush.local\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid email or password"));
    }

    @Test
    void tamperedToken_returns401() throws Exception {
        String token = login("{\"email\":\"admin@tixrush.local\",\"password\":\"admin12345\"}");
        String tampered = token.substring(0, token.length() - 2) + "xx"; // break the signature

        mockMvc.perform(post("/api/events").header("Authorization", "Bearer " + tampered)
                        .contentType(MediaType.APPLICATION_JSON).content(EVENT_BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateEmail_returns409() throws Exception {
        String creds = "{\"email\":\"dup-" + UUID.randomUUID() + "@example.com\",\"password\":\"supersecret\"}";

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(creds))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(creds))
                .andExpect(status().isConflict());
    }

    private String login(String creds) throws Exception {
        String json = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(creds))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.token");
    }
}
