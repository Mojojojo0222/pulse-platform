package com.pulse.api;

import com.pulse.prometheus.PrometheusException;
import com.pulse.service.AppMetricsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AppMetricsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppMetricsService metricsService;

    @Test
    void listsConfiguredApps() throws Exception {
        mockMvc.perform(get("/api/v1/apps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apps[0].name").value("todoapp"))
                .andExpect(jsonPath("$.apps[0].job").value("todoapp"));
    }

    @Test
    void returnsMetricsSnapshot() throws Exception {
        when(metricsService.snapshot("todoapp")).thenReturn(new AppMetricsService.Snapshot(
                "todoapp", "2026-11-13T00:00:00Z", "UP",
                2.0, 0.05, 0.5, null, "5m",
                "http://localhost:9090", List.of("p95 unavailable: no histogram buckets")));

        mockMvc.perform(get("/api/v1/apps/todoapp/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("todoapp"))
                .andExpect(jsonPath("$.target").value("UP"))
                .andExpect(jsonPath("$.requestRatePerSecond").value(2.0))
                .andExpect(jsonPath("$.errorRatio").value(0.05))
                .andExpect(jsonPath("$.p95LatencySeconds").doesNotExist())
                .andExpect(jsonPath("$.notes[0]").value("p95 unavailable: no histogram buckets"));
    }

    @Test
    void unknownAppReturns404() throws Exception {
        mockMvc.perform(get("/api/v1/apps/ghost/metrics"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("unknown_application"))
                .andExpect(jsonPath("$.application").value("ghost"));
    }

    @Test
    void prometheusDownReturns503() throws Exception {
        when(metricsService.snapshot("todoapp"))
                .thenThrow(PrometheusException.unavailable("Prometheus unreachable (refused)", null));

        mockMvc.perform(get("/api/v1/apps/todoapp/metrics"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("prometheus_unavailable"));
    }

    @Test
    void queryFailureReturns502() throws Exception {
        when(metricsService.snapshot("todoapp"))
                .thenThrow(PrometheusException.queryFailed("Prometheus rejected query: parse error"));

        mockMvc.perform(get("/api/v1/apps/todoapp/metrics"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("prometheus_query_failed"));
    }
}
