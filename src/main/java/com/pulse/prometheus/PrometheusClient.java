package com.pulse.prometheus;

import com.pulse.config.PulseProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class PrometheusClient {

    private static final int MAX_ERROR_BODY = 300;

    private final RestClient rest;
    private final String baseUrl;

    @Autowired
    public PrometheusClient(PulseProperties properties) {
        this.rest = buildRest(properties);
        this.baseUrl = stripTrailingSlash(properties.getPrometheus().getBaseUrl());
    }

    PrometheusClient(RestClient rest, String baseUrl) {
        this.rest = rest;
        this.baseUrl = baseUrl;
    }

    private static RestClient buildRest(PulseProperties properties) {
        PulseProperties.Prometheus cfg = properties.getPrometheus();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) cfg.getConnectTimeoutMs());
        factory.setReadTimeout((int) cfg.getReadTimeoutMs());
        return RestClient.builder()
                .baseUrl(stripTrailingSlash(cfg.getBaseUrl()))
                .requestFactory(factory)
                .build();
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public VectorResult instantQuery(String promql) {
        String encodedQuery = URLEncoder.encode(promql, StandardCharsets.UTF_8);
        URI target = URI.create(baseUrl + "/api/v1/query?query=" + encodedQuery);

        PromResponse response;
        try {
            response = rest.get()
                    .uri(target)
                    .retrieve()
                    .body(PromResponse.class);
        } catch (ResourceAccessException e) {
            throw PrometheusException.unavailable(
                    "Prometheus unreachable (" + e.getMessage() + ")", e);
        } catch (RestClientResponseException e) {
            throw PrometheusException.queryFailed(
                    "Prometheus HTTP " + e.getStatusCode().value() + ": "
                            + truncate(e.getResponseBodyAsString()), e);
        } catch (RestClientException e) {
            throw PrometheusException.queryFailed(
                    "Invalid Prometheus response: " + e.getMessage(), e);
        }

        if (response == null || response.status() == null) {
            throw PrometheusException.queryFailed("Empty response from Prometheus");
        }
        if ("error".equals(response.status())) {
            String detail = response.error() != null ? response.error() : "unknown query error";
            if (response.errorType() != null) {
                detail = response.errorType() + ": " + detail;
            }
            throw PrometheusException.queryFailed("Prometheus rejected query: " + detail);
        }

        List<Sample> samples = new ArrayList<>();
        PromData data = response.data();
        if (data != null && data.result() != null) {
            try {
                for (PromResult result : data.result()) {
                    if (result == null || result.value() == null || result.value().size() < 2) {
                        continue;
                    }
                    double value = parseValue(String.valueOf(result.value().get(1)));
                    Map<String, String> labels = result.metric() != null ? result.metric() : Map.of();
                    samples.add(new Sample(labels, value));
                }
            } catch (NumberFormatException e) {
                throw PrometheusException.queryFailed("Malformed sample value in response", e);
            }
        }
        return new VectorResult(data != null ? data.resultType() : null, samples);
    }

    static double parseValue(String raw) {
        if (raw == null) {
            return Double.NaN;
        }
        return switch (raw) {
            case "NaN" -> Double.NaN;
            case "+Inf" -> Double.POSITIVE_INFINITY;
            case "-Inf" -> Double.NEGATIVE_INFINITY;
            default -> Double.parseDouble(raw);
        };
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > MAX_ERROR_BODY ? body.substring(0, MAX_ERROR_BODY) + "..." : body;
    }

    public record VectorResult(String resultType, List<Sample> samples) {
        public boolean isEmpty() {
            return samples == null || samples.isEmpty();
        }
    }

    public record Sample(Map<String, String> labels, double value) {}

    public record PromResponse(String status, PromData data, String errorType, String error) {}

    public record PromData(String resultType, List<PromResult> result) {}

    public record PromResult(Map<String, String> metric, List<Object> value) {}
}
