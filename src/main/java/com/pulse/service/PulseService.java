package com.pulse.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class PulseService {
    @Value("${app.version:1.0.0}")
    private String version;

    public String version() {
        return version;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("application", "pulse-api");
        result.put("status", "UP");
        result.put("version", version);
        result.put("instance", System.getenv().getOrDefault("HOSTNAME", "local"));
        result.put("timestamp", Instant.now().toString());
        result.put("message", "Pulse is alive and serving requests");
        return result;
    }
}
