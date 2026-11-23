package com.tixrush;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The React website is served by the same server as the API (see SpaController): its pages are
 * open to everyone and answered with index.html, while /api paths are never mistaken for pages.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class WebsiteRoutesTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void websitePages_areAnsweredWithTheApp_withoutLogin() throws Exception {
        for (String page : new String[] {"/", "/events/87", "/my-tickets", "/orders/abc-123", "/practice"}) {
            mockMvc.perform(get(page)).andExpect(status().isOk()).andExpect(forwardedUrl("/index.html"));
        }
    }

    @Test
    void apiAndFiles_areNotTurnedIntoPages() throws Exception {
        mockMvc.perform(get("/api/nope")).andExpect(status().isUnauthorized()).andExpect(forwardedUrl(null));
        mockMvc.perform(get("/api/events")).andExpect(status().isOk()).andExpect(forwardedUrl(null));
        mockMvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound()).andExpect(forwardedUrl(null));
    }
}
