package com.pulse.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "pulse")
public class PulseProperties {

    private final Prometheus prometheus = new Prometheus();
    private final Kubernetes kubernetes = new Kubernetes();
    private Map<String, App> apps = new LinkedHashMap<>();

    public Prometheus getPrometheus() {
        return prometheus;
    }

    public Kubernetes getKubernetes() {
        return kubernetes;
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

    public static class Kubernetes {
        private boolean enabled = false;
        private String authMode = "in-cluster";
        private String baseUrl = "";
        private String namespace = "pulse-system";
        private String tokenFile = "/var/run/secrets/kubernetes.io/serviceaccount/token";
        private String caCertFile = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt";
        private String kubeconfigPath = "";
        private long connectTimeoutMs = 2000;
        private long readTimeoutMs = 5000;
        private long deadlineMs = 8000;
        private int pageSize = 500;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getAuthMode() {
            return authMode;
        }

        public void setAuthMode(String authMode) {
            this.authMode = authMode;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getNamespace() {
            return namespace;
        }

        public void setNamespace(String namespace) {
            this.namespace = namespace;
        }

        public String getTokenFile() {
            return tokenFile;
        }

        public void setTokenFile(String tokenFile) {
            this.tokenFile = tokenFile;
        }

        public String getCaCertFile() {
            return caCertFile;
        }

        public void setCaCertFile(String caCertFile) {
            this.caCertFile = caCertFile;
        }

        public String getKubeconfigPath() {
            return kubeconfigPath;
        }

        public void setKubeconfigPath(String kubeconfigPath) {
            this.kubeconfigPath = kubeconfigPath;
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

        public long getDeadlineMs() {
            return deadlineMs;
        }

        public void setDeadlineMs(long deadlineMs) {
            this.deadlineMs = deadlineMs;
        }

        public int getPageSize() {
            return pageSize;
        }

        public void setPageSize(int pageSize) {
            this.pageSize = pageSize;
        }
    }
}
