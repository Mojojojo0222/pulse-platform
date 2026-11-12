package com.pulse.service;

import com.pulse.config.PulseProperties;
import com.pulse.prometheus.PrometheusClient;
import com.pulse.prometheus.PrometheusClient.VectorResult;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class AppMetricsService {

    private final PulseProperties properties;
    private final PrometheusClient prometheus;

    public AppMetricsService(PulseProperties properties, PrometheusClient prometheus) {
        this.properties = properties;
        this.prometheus = prometheus;
    }

    public record Snapshot(
            String application,
            String observedAt,
            String target,
            Double requestRatePerSecond,
            Double errorRatio,
            Double avgLatencySeconds,
            Double p95LatencySeconds,
            String queryWindow,
            String prometheusUrl,
            List<String> notes
    ) {}

    public Snapshot snapshot(String appName) {
        PulseProperties.App app = properties.getApps().get(appName);
        if (app == null) {
            throw new IllegalArgumentException("unknown application: " + appName);
        }
        String job = app.getJob();
        if (job == null || job.isBlank()) {
            throw new IllegalStateException("no scrape job configured for application: " + appName);
        }
        String window = properties.getPrometheus().getQueryWindow();
        List<String> notes = new ArrayList<>();

        String jobSelector = "job=\"" + escapeLabelValue(job) + "\"";
        String countSeries = "http_server_requests_seconds_count{" + jobSelector + "}";

        VectorResult up = prometheus.instantQuery("up{" + jobSelector + "}");
        String target;
        if (up.isEmpty()) {
            target = "UNKNOWN";
            notes.add("No data: scrape job '" + job + "' has never been scraped");
        } else {
            target = up.samples().get(0).value() >= 0.5 ? "UP" : "DOWN";
        }

        Double requestRate = finite(firstValue(prometheus.instantQuery(
                "sum(rate(" + countSeries + "[" + window + "]))")));
        if (requestRate == null) {
            notes.add("No request metric series found for job '" + job + "'");
        }

        Double errorRate = finite(firstValue(prometheus.instantQuery(
                "sum(rate(http_server_requests_seconds_count{" + jobSelector
                        + ",status=~\"5..\"}[" + window + "]))")));
        Double errorRatio = null;
        if (requestRate != null) {
            if (requestRate > 0) {
                // Absent 5xx series means zero errors, not unknown errors.
                errorRatio = errorRate == null ? 0.0 : errorRate / requestRate;
            } else {
                notes.add("No request traffic in the last " + window);
            }
        }

        Double avgLatency = null;
        if (requestRate != null && requestRate > 0) {
            Double latencySumRate = finite(firstValue(prometheus.instantQuery(
                    "sum(rate(http_server_requests_seconds_sum{" + jobSelector + "}[" + window + "]))")));
            if (latencySumRate != null) {
                avgLatency = latencySumRate / requestRate;
            }
        }

        Double p95 = finite(firstValue(prometheus.instantQuery(
                "histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{"
                        + jobSelector + "}[" + window + "])))")));
        if (p95 == null) {
            notes.add("p95 unavailable: application does not expose histogram buckets");
        }

        return new Snapshot(
                appName,
                Instant.now().toString(),
                target,
                requestRate,
                errorRatio,
                avgLatency,
                p95,
                window,
                properties.getPrometheus().getBaseUrl(),
                List.copyOf(notes)
        );
    }

    private static Double firstValue(VectorResult result) {
        if (result == null || result.isEmpty()) {
            return null;
        }
        return result.samples().get(0).value();
    }

    private static Double finite(Double value) {
        if (value == null || value.isNaN() || value.isInfinite()) {
            return null;
        }
        return value;
    }

    private static String escapeLabelValue(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
