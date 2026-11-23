package com.tixrush;

import org.springframework.context.annotation.Import;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The visualizer's static files are public, but the rush API behind them stays admin-only. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "tixrush.dev-tools.enabled=true")
@AutoConfigureMockMvc
class VisualizerPageTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void pageAndAssets_needNoToken() throws Exception {
        mockMvc.perform(get("/visualizer/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Rush Visualizer")));
        mockMvc.perform(get("/visualizer/app.js")).andExpect(status().isOk());
        mockMvc.perform(get("/visualizer/")).andExpect(forwardedUrl("/visualizer/index.html"));
    }

    @Test
    void rushApi_withoutToken_is401() throws Exception {
        mockMvc.perform(get("/api/admin/rush")).andExpect(status().isUnauthorized());
    }
}
