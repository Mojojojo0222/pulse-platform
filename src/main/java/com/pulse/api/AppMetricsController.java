package com.pulse.api;

import com.pulse.config.PulseProperties;
import com.pulse.prometheus.PrometheusException;
import com.pulse.service.AppMetricsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class AppMetricsController {

    private final PulseProperties properties;
    private final AppMetricsService metricsService;

    public AppMetricsController(PulseProperties properties, AppMetricsService metricsService) {
        this.properties = properties;
        this.metricsService = metricsService;
    }

    @GetMapping("/api/v1/apps")
    public Map<String, Object> apps() {
        List<Map<String, String>> list = properties.getApps().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> Map.of(
                        "name", entry.getKey(),
                        "job", entry.getValue().getJob() != null ? entry.getValue().getJob() : ""))
                .toList();
        return Map.of("apps", list);
    }

    @GetMapping("/api/v1/apps/{name}/metrics")
    public ResponseEntity<Object> metrics(@PathVariable String name) {
        if (!properties.getApps().containsKey(name)) {
            return ResponseEntity.status(404).body(
                    Map.of("error", "unknown_application", "application", name));
        }
        try {
            return ResponseEntity.ok(metricsService.snapshot(name));
        } catch (PrometheusException e) {
            boolean unavailable = e.getKind() == PrometheusException.Kind.UNAVAILABLE;
            String code = unavailable ? "prometheus_unavailable" : "prometheus_query_failed";
            int status = unavailable ? 503 : 502;
            return ResponseEntity.status(status).body(
                    Map.of("error", code, "detail", String.valueOf(e.getMessage())));
        }
    }
}
