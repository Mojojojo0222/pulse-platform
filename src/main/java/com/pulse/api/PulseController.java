package com.pulse.api;

import com.pulse.service.PulseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class PulseController {
    private final PulseService pulseService;

    public PulseController(PulseService pulseService) {
        this.pulseService = pulseService;
    }

    @GetMapping("/")
    public Map<String, Object> index() {
        return Map.of(
                "application", "Pulse Platform API",
                "description", "Platform health and deployment identity service",
                "status", "UP",
                "version", pulseService.version(),
                "endpoints", Map.of(
                        "pulse", "/api/v1/pulse",
                        "apps", "/api/v1/apps",
                        "appMetrics", "/api/v1/apps/{name}/metrics",
                        "liveness", "/health/live",
                        "readiness", "/health/ready",
                        "actuator", "/actuator/health"
                )
        );
    }

    @GetMapping("/api/v1/pulse")
    public Map<String, Object> pulse() {
        return pulseService.snapshot();
    }

    @GetMapping("/health/live")
    public Map<String, Object> liveness() {
        return Map.of("status", "UP", "check", "liveness");
    }

    @GetMapping("/health/ready")
    public Map<String, Object> readiness() {
        return Map.of("status", "UP", "check", "readiness");
    }

    @GetMapping(value = "/ui", produces = "text/html;charset=UTF-8")
    public String ui() {
        Map<String, Object> s = pulseService.snapshot();
        String version = String.valueOf(s.get("version"));
        String instance = String.valueOf(s.get("instance"));
        String status = String.valueOf(s.get("status"));
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="UTF-8"/>
                  <title>Pulse %s</title>
                  <style>
                    body{font-family:system-ui,sans-serif;background:#0f172a;display:grid;place-items:center;height:100vh;margin:0;color:#e2e8f0}
                    .card{background:#1e293b;border:1px solid #334155;border-radius:12px;padding:2rem 2.5rem;min-width:320px}
                    h1{margin:0 0 .5rem;font-size:1.4rem}
                    .badge{display:inline-block;background:#22c55e;color:#052e16;font-weight:700;padding:.25rem .75rem;border-radius:999px;font-size:.85rem}
                    dl{display:grid;grid-template-columns:auto 1fr;gap:.4rem 1rem;margin:1.25rem 0 0}
                    dt{color:#94a3b8}dd{margin:0;font-family:ui-monospace,monospace}
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>Pulse Platform <span class="badge">%s</span></h1>
                    <dl>
                      <dt>Status</dt><dd>%s</dd>
                      <dt>Version</dt><dd>%s</dd>
                      <dt>Instance</dt><dd>%s</dd>
                    </dl>
                  </div>
                </body>
                </html>
                """.formatted(version, version, status, version, instance);
    }
}
