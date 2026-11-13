package com.pulse.kubernetes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.config.PulseProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Read-only Kubernetes observer. Pulls Deployment/Pod/Event lists on demand
 * (no watch, no cache, no background threads), follows metadata.continue
 * pagination, and bounds every observation with a {@link Deadline}: each call's
 * read timeout is clamped to the remaining budget, so the sequence of calls
 * cannot outlive the deadline. All upstream failures are mapped into
 * {@link KubernetesException} kinds — raw API server bodies are never propagated.
 */
@Component
public class HttpKubernetesClient implements KubernetesClient {

    private static final int MAX_ERROR_DETAIL = 300;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean enabled;
    private final RestClientProvider restProvider;
    private final String baseUrl;
    private final long readTimeoutMs;
    private final int pageSize;
    private final Supplier<String> tokenSupplier;
    private final java.net.http.HttpClient httpClient;

    @Autowired
    public HttpKubernetesClient(PulseProperties properties) {
        PulseProperties.Kubernetes cfg = properties.getKubernetes();
        this.enabled = cfg.isEnabled();
        this.readTimeoutMs = cfg.getReadTimeoutMs();
        this.pageSize = cfg.getPageSize();
        if (!enabled) {
            // Disabled = start normally, touch no credential files, make no calls.
            this.restProvider = null;
            this.baseUrl = null;
            this.tokenSupplier = null;
            this.httpClient = null;
            return;
        }
        if (pageSize <= 0) {
            throw new KubernetesConfigurationException("pulse.kubernetes.page-size must be positive, got " + pageSize);
        }
        if (readTimeoutMs <= 0 || cfg.getConnectTimeoutMs() <= 0) {
            throw new KubernetesConfigurationException(
                    "pulse.kubernetes timeouts must be positive (connect=" + cfg.getConnectTimeoutMs()
                            + ", read=" + readTimeoutMs + ")");
        }
        if (cfg.getDeadlineMs() <= 0) {
            throw new KubernetesConfigurationException(
                    "pulse.kubernetes.deadline-ms must be positive, got " + cfg.getDeadlineMs());
        }

        KubernetesAuth.Resolved auth = KubernetesAuth.resolve(cfg);
        String configured = cfg.getBaseUrl();
        String server = configured != null && !configured.isBlank() ? configured : auth.serverUrl();
        this.baseUrl = stripTrailingSlash(server);
        this.tokenSupplier = auth.tokenSupplier();
        this.httpClient = buildHttpClient(cfg.getConnectTimeoutMs(), auth.sslContext());
        this.restProvider = this::buildRestClient;
    }

    /** Test seam: inject a provider (typically a MockRestServiceServer-backed RestClient). */
    HttpKubernetesClient(RestClientProvider restProvider, String baseUrl, int pageSize, long readTimeoutMs) {
        this.enabled = true;
        this.restProvider = Objects.requireNonNull(restProvider, "restProvider");
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.pageSize = pageSize;
        this.readTimeoutMs = readTimeoutMs;
        this.tokenSupplier = () -> "test-token";
        this.httpClient = null;
    }

    @FunctionalInterface
    interface RestClientProvider {
        RestClient create(long readTimeoutMs);
    }

    // --- the three read operations -----------------------------------------

    @Override
    public List<DeploymentInfo> listDeployments(String namespace, Deadline deadline) {
        requireEnabled();
        String path = "/apis/apps/v1/namespaces/" + encodeSegment(namespace) + "/deployments";
        return listAll(path, null, null, deadline, HttpKubernetesClient::mapDeployment, "deployments");
    }

    @Override
    public List<PodInfo> listPods(String namespace, String labelSelector, Deadline deadline) {
        requireEnabled();
        String path = "/api/v1/namespaces/" + encodeSegment(namespace) + "/pods";
        return listAll(path, "labelSelector", labelSelector, deadline, HttpKubernetesClient::mapPod, "pods");
    }

    @Override
    public List<EventInfo> listEvents(String namespace, String fieldSelector, Deadline deadline) {
        requireEnabled();
        String path = "/api/v1/namespaces/" + encodeSegment(namespace) + "/events";
        return listAll(path, "fieldSelector", fieldSelector, deadline, HttpKubernetesClient::mapEvent, "events");
    }

    private void requireEnabled() {
        if (!enabled) {
            throw KubernetesException.disabled("Kubernetes observer disabled (pulse.kubernetes.enabled=false)");
        }
    }

    // --- pagination ---------------------------------------------------------

    private <T> List<T> listAll(String path, String selectorParam, String selector, Deadline deadline,
                                Function<JsonNode, T> mapper, String what) {
        Objects.requireNonNull(deadline, "deadline — one Deadline must be created per observation and reused");
        List<T> collected = new ArrayList<>();
        String continueToken = null;
        int goneRestarts = 0;
        while (true) {
            if (deadline.isExpired()) {
                throw KubernetesException.deadlineExceeded(
                        "deadline exceeded before fetching " + what + " (budget spent on earlier calls)", null);
            }
            StringBuilder url = new StringBuilder(path).append("?limit=").append(pageSize);
            if (selector != null && !selector.isBlank()) {
                url.append('&').append(selectorParam).append('=')
                        .append(URLEncoder.encode(selector, StandardCharsets.UTF_8));
            }
            if (continueToken != null) {
                url.append("&continue=").append(URLEncoder.encode(continueToken, StandardCharsets.UTF_8));
            }

            JsonNode root;
            try {
                root = getPage(url.toString(), deadline, what);
            } catch (ContinueGoneException e) {
                // Continuation tokens expire (410 Gone). One full restart from page 1
                // is safe for a read-only list; a second 410 means we cannot finish
                // honestly → fail instead of returning a silently truncated list.
                if (continueToken != null && goneRestarts == 0) {
                    goneRestarts++;
                    collected.clear();
                    continueToken = null;
                    continue;
                }
                throw KubernetesException.badResponse(
                        "stale continuation token (HTTP 410) while paging " + what + ": " + e.getMessage(), e);
            }

            JsonNode items = root.get("items");
            if (items == null || !items.isArray()) {
                throw KubernetesException.badResponse(
                        "Kubernetes " + what + " response has no 'items' array — refusing to guess completeness");
            }
            for (JsonNode item : items) {
                if (item == null || !item.isObject()) {
                    throw KubernetesException.badResponse(
                            "Kubernetes " + what + " response contains a malformed item entry");
                }
                collected.add(mapper.apply(item));
            }

            JsonNode continueNode = root.path("metadata").get("continue");
            String next = continueNode != null && continueNode.isTextual() ? continueNode.asText() : "";
            if (next == null || next.isEmpty()) {
                return List.copyOf(collected);
            }
            continueToken = next;
        }
    }

    private JsonNode getPage(String pathAndQuery, Deadline deadline, String what) {
        if (deadline.isExpired()) {
            throw KubernetesException.deadlineExceeded("deadline exceeded before fetching " + what, null);
        }
        // Clamp this call's read timeout to the remaining budget: sequential pages
        // can therefore never push the observation past the deadline.
        long remaining = deadline.remainingMillis();
        long clampedRead = Math.max(1L, Math.min(readTimeoutMs, remaining));
        RestClient rest = restProvider.create(clampedRead);

        String token;
        try {
            token = tokenSupplier.get();
        } catch (KubernetesConfigurationException e) {
            throw KubernetesException.unauthenticated("Kubernetes credentials unreadable: " + e.getMessage(), e);
        }

        try {
            JsonNode body = rest.get()
                    .uri(URI.create(baseUrl + pathAndQuery))
                    .header("Authorization", "Bearer " + token)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(JsonNode.class);
            if (body == null) {
                throw KubernetesException.badResponse("empty response body from Kubernetes for " + what);
            }
            if (!body.isObject()) {
                throw KubernetesException.badResponse(
                        "non-object JSON response from Kubernetes for " + what + " — expected a list object");
            }
            return body;
        } catch (ResourceAccessException e) {
            if (deadline.isExpired()) {
                throw KubernetesException.deadlineExceeded(
                        "deadline exceeded during " + what + " (" + clampedRead + "ms budget): " + e.getMessage(), e);
            }
            throw KubernetesException.unavailable("Kubernetes unreachable (" + e.getMessage() + ")", e);
        } catch (RestClientResponseException e) {
            throw mapHttpError(e, what);
        } catch (RestClientException e) {
            throw KubernetesException.badResponse(
                    "malformed Kubernetes response for " + what + ": " + truncate(e.getMessage()), e);
        }
    }

    private static RuntimeException mapHttpError(RestClientResponseException e, String what) {
        int status = e.getStatusCode().value();
        String detail = statusDetail(e, status, what);
        return switch (status) {
            case 401 -> KubernetesException.unauthenticated(detail);
            case 403 -> KubernetesException.forbidden(detail);
            case 404 -> KubernetesException.notFound(detail);
            case 410 -> new ContinueGoneException(detail);
            case 429 -> KubernetesException.throttled(detail);
            default -> status >= 500
                    ? KubernetesException.upstreamError(detail)
                    : KubernetesException.badResponse(detail);
        };
    }

    /** Sanitized diagnostics: status code + upstream Status.reason/message (or raw body), truncated. */
    private static String statusDetail(RestClientResponseException e, int status, String what) {
        StringBuilder detail = new StringBuilder("Kubernetes HTTP ").append(status);
        String retryAfter = e.getResponseHeaders() != null ? e.getResponseHeaders().getFirst("Retry-After") : null;
        if (retryAfter != null && !retryAfter.isBlank()) {
            detail.append(" (Retry-After: ").append(retryAfter).append(')');
        }
        detail.append(" for ").append(what);

        String body = e.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return truncate(detail.toString());
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node != null && node.isObject()) {
                String reason = text(node, "reason");
                String message = text(node, "message");
                if (reason != null || message != null) {
                    detail.append(": ").append(reason != null ? reason : "");
                    if (reason != null && message != null) {
                        detail.append(": ");
                    }
                    if (message != null) {
                        detail.append(message);
                    }
                    return truncate(detail.toString());
                }
            }
        } catch (Exception ignore) {
            // fall through to raw (truncated) body
        }
        detail.append(": ").append(body);
        return truncate(detail.toString());
    }

    private static final class ContinueGoneException extends RuntimeException {
        ContinueGoneException(String message) {
            super(message);
        }
    }

    // --- JSON → records (only the fields Pulse needs) -----------------------

    private static DeploymentInfo mapDeployment(JsonNode d) {
        JsonNode meta = d.path("metadata");
        JsonNode spec = d.path("spec");
        JsonNode status = d.path("status");

        Integer revision = null;
        String rawRevision = meta.path("annotations").path("deployment.kubernetes.io/revision").asText(null);
        if (rawRevision != null) {
            try {
                revision = Integer.parseInt(rawRevision.trim());
            } catch (NumberFormatException ignore) {
                // annotation present but not a number → leave null, never guess
            }
        }

        String image = null;
        JsonNode containers = spec.path("template").path("spec").path("containers");
        if (containers.isArray() && !containers.isEmpty()) {
            image = containers.get(0).path("image").asText(null);
        }

        Map<String, String> podSelector = new java.util.LinkedHashMap<>();
        JsonNode matchLabels = spec.path("selector").path("matchLabels");
        if (matchLabels.isObject()) {
            matchLabels.fields().forEachRemaining(entry ->
                    podSelector.put(entry.getKey(), entry.getValue().asText()));
        }

        List<DeploymentCondition> conditions = new ArrayList<>();
        JsonNode rawConditions = status.path("conditions");
        if (rawConditions.isArray()) {
            for (JsonNode condition : rawConditions) {
                conditions.add(new DeploymentCondition(
                        condition.path("type").asText(null),
                        condition.path("status").asText(null),
                        condition.path("reason").asText(null),
                        condition.path("message").asText(null)));
            }
        }

        return new DeploymentInfo(
                meta.path("uid").asText(null),
                meta.path("name").asText(null),
                meta.path("namespace").asText(null),
                meta.path("resourceVersion").asText(null),
                meta.path("generation").asLong(0),
                status.path("observedGeneration").asLong(0),
                revision,
                image,
                spec.path("replicas").asInt(0),
                status.path("readyReplicas").asInt(0),
                status.path("availableReplicas").asInt(0),
                status.path("updatedReplicas").asInt(0),
                meta.path("creationTimestamp").asText(null),
                Map.copyOf(podSelector),
                List.copyOf(conditions));
    }

    private static PodInfo mapPod(JsonNode p) {
        JsonNode meta = p.path("metadata");
        JsonNode spec = p.path("spec");
        JsonNode status = p.path("status");

        boolean allReady = false;
        int restarts = 0;
        String waitingReason = null;
        JsonNode containerStatuses = status.path("containerStatuses");
        if (containerStatuses.isArray() && !containerStatuses.isEmpty()) {
            allReady = true;
            for (JsonNode cs : containerStatuses) {
                if (!cs.path("ready").asBoolean(false)) {
                    allReady = false;
                }
                restarts += cs.path("restartCount").asInt(0);
                if (waitingReason == null) {
                    waitingReason = cs.path("state").path("waiting").path("reason").asText(null);
                }
            }
        }

        String ownerName = null;
        JsonNode owners = meta.path("ownerReferences");
        if (owners.isArray() && !owners.isEmpty()) {
            ownerName = owners.get(0).path("name").asText(null);
        }

        return new PodInfo(
                meta.path("uid").asText(null),
                meta.path("name").asText(null),
                meta.path("namespace").asText(null),
                meta.path("resourceVersion").asText(null),
                status.path("phase").asText(null),
                allReady,
                restarts,
                waitingReason,
                spec.path("nodeName").asText(null),
                meta.path("creationTimestamp").asText(null),
                ownerName);
    }

    private static EventInfo mapEvent(JsonNode ev) {
        JsonNode meta = ev.path("metadata");
        JsonNode involved = ev.path("involvedObject");
        return new EventInfo(
                meta.path("uid").asText(null),
                ev.path("type").asText(null),
                ev.path("reason").asText(null),
                ev.path("message").asText(null),
                ev.path("count").asInt(0),
                ev.path("firstTimestamp").asText(null),
                ev.path("lastTimestamp").asText(null),
                involved.path("kind").asText(null),
                involved.path("name").asText(null));
    }

    // --- plumbing -----------------------------------------------------------

    private static java.net.http.HttpClient buildHttpClient(long connectTimeoutMs,
                                                            javax.net.ssl.SSLContext sslContext) {
        java.net.http.HttpClient.Builder builder = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs));
        if (sslContext != null) {
            builder.sslContext(sslContext);
        }
        return builder.build();
    }

    private RestClient buildRestClient(long clampedReadTimeoutMs) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(clampedReadTimeoutMs));
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory((ClientHttpRequestFactory) factory)
                .build();
    }

    private static String encodeSegment(String segment) {
        if (segment == null || segment.isBlank()) {
            throw new IllegalArgumentException("namespace must not be blank");
        }
        return UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static String truncate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() > MAX_ERROR_DETAIL ? body.substring(0, MAX_ERROR_DETAIL) + "..." : body;
    }
}
