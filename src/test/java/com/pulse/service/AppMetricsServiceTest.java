package com.pulse.service;

import com.pulse.config.PulseProperties;
import com.pulse.prometheus.PrometheusClient;
import com.pulse.prometheus.PrometheusClient.Sample;
import com.pulse.prometheus.PrometheusClient.VectorResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AppMetricsServiceTest {

    private PrometheusClient client;
    private AppMetricsService service;

    @BeforeEach
    void setUp() {
        PulseProperties properties = new PulseProperties();
        PulseProperties.App app = new PulseProperties.App();
        app.setJob("todoapp");
        properties.getApps().put("todoapp", app);

        client = mock(PrometheusClient.class);
        service = new AppMetricsService(properties, client);
    }

    @Test
    void healthyAppWithTraffic() {
        when(client.instantQuery(contains("up{"))).thenReturn(vector(1.0));
        when(client.instantQuery(contains("count{job=\"todoapp\"}["))).thenReturn(vector(2.0));
        when(client.instantQuery(contains("status=~"))).thenReturn(vector(0.1));
        when(client.instantQuery(contains("_sum{"))).thenReturn(vector(1.0));
        when(client.instantQuery(contains("histogram_quantile"))).thenReturn(empty());

        AppMetricsService.Snapshot snapshot = service.snapshot("todoapp");

        assertEquals("UP", snapshot.target());
        assertEquals(2.0, snapshot.requestRatePerSecond());
        assertEquals(0.05, snapshot.errorRatio());
        assertEquals(0.5, snapshot.avgLatencySeconds());
        assertNull(snapshot.p95LatencySeconds());
        assertTrue(snapshot.notes().stream().anyMatch(n -> n.contains("p95 unavailable")));
        assertEquals("5m", snapshot.queryWindow());
    }

    @Test
    void noDataMeansUnknownTarget() {
        when(client.instantQuery(contains("up{"))).thenReturn(empty());
        when(client.instantQuery(contains("count{job=\"todoapp\"}["))).thenReturn(empty());
        when(client.instantQuery(contains("status=~"))).thenReturn(empty());
        when(client.instantQuery(contains("_sum{"))).thenReturn(empty());
        when(client.instantQuery(contains("histogram_quantile"))).thenReturn(empty());

        AppMetricsService.Snapshot snapshot = service.snapshot("todoapp");

        assertEquals("UNKNOWN", snapshot.target());
        assertNull(snapshot.requestRatePerSecond());
        assertNull(snapshot.errorRatio());
        assertTrue(snapshot.notes().stream().anyMatch(n -> n.contains("never been scraped")));
    }

    @Test
    void targetDownWhenUpIsZero() {
        when(client.instantQuery(contains("up{"))).thenReturn(vector(0.0));
        when(client.instantQuery(contains("count{job=\"todoapp\"}["))).thenReturn(vector(0.0));
        when(client.instantQuery(contains("status=~"))).thenReturn(vector(0.0));
        when(client.instantQuery(contains("_sum{"))).thenReturn(empty());
        when(client.instantQuery(contains("histogram_quantile"))).thenReturn(empty());

        AppMetricsService.Snapshot snapshot = service.snapshot("todoapp");

        assertEquals("DOWN", snapshot.target());
        assertNull(snapshot.errorRatio());
        assertTrue(snapshot.notes().stream().anyMatch(n -> n.contains("No request traffic")));
    }

    @Test
    void absentErrorSeriesMeansZeroRatio() {
        when(client.instantQuery(contains("up{"))).thenReturn(vector(1.0));
        when(client.instantQuery(contains("count{job=\"todoapp\"}["))).thenReturn(vector(2.0));
        when(client.instantQuery(contains("status=~"))).thenReturn(empty());
        when(client.instantQuery(contains("_sum{"))).thenReturn(vector(1.0));
        when(client.instantQuery(contains("histogram_quantile"))).thenReturn(empty());

        AppMetricsService.Snapshot snapshot = service.snapshot("todoapp");

        assertEquals(0.0, snapshot.errorRatio());
        assertEquals(0.5, snapshot.avgLatencySeconds());
    }

    @Test
    void unknownAppRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.snapshot("ghost"));
    }

    private static VectorResult vector(double value) {
        return new VectorResult("vector", List.of(new Sample(Map.of("job", "todoapp"), value)));
    }

    private static VectorResult empty() {
        return new VectorResult("vector", List.of());
    }
}
