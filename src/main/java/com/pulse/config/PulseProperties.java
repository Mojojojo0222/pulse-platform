package com.pulse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "pulse")
public class PulseProperties {

    private final Prometheus prometheus = new Prometheus();
    private Map<String, App> apps = new LinkedHashMap<>();

    public Prometheus getPrometheus() {
        return prometheus;
    }

    public Map<String, App> getApps() {
        return apps;
    }

    public void setApps(Map<String, App> apps) {
        this.apps = apps;
    }

    public static class Prometheus {
        private String baseUrl = "http://localhost:9090";
        private long connectTimeoutMs = 2000;
        private long readTimeoutMs = 5000;
        private String queryWindow = "5m";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public long getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public long getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(long readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }

        public String getQueryWindow() {
            return queryWindow;
        }

        public void setQueryWindow(String queryWindow) {
            this.queryWindow = queryWindow;
        }
    }

    public static class App {
        private String job;

        public String getJob() {
            return job;
        }

        public void setJob(String job) {
            this.job = job;
        }
    }
}
