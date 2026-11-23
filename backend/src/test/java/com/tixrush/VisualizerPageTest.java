package com.tixrush;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The visualizer's static files are public, but the rush API behind them stays admin-only. */
@SpringBootTest(properties = "tixrush.dev-tools.enabled=true")
@AutoConfigureMockMvc
class VisualizerPageTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void pageAndAssets_needNoToken() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Rush Visualizer")));
        mockMvc.perform(get("/visualizer/app.js")).andExpect(status().isOk());
        mockMvc.perform(get("/")).andExpect(status().isOk());
    }

    @Test
    void rushApi_withoutToken_is401() throws Exception {
        mockMvc.perform(get("/api/admin/rush")).andExpect(status().isUnauthorized());
    }
}
