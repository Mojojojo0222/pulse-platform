package com.pulse.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PulseControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void pulseEndpointReturnsHealthyPayload() throws Exception {
        mockMvc.perform(get("/api/v1/pulse"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("pulse-api"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.version").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void readinessEndpointReturnsUp() throws Exception {
        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
