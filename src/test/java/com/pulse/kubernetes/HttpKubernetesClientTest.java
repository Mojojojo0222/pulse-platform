package com.pulse.kubernetes;

import com.pulse.config.PulseProperties;
import com.pulse.kubernetes.KubernetesClient.DeploymentInfo;
import com.pulse.kubernetes.KubernetesClient.EventInfo;
import com.pulse.kubernetes.KubernetesClient.PodInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ResponseActions;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class HttpKubernetesClientTest {

    private static final String NS = "pulse-system";
    private static final String DEPLOYMENTS_PATH = "/apis/apps/v1/namespaces/pulse-system/deployments";
    private static final String PODS_PATH = "/api/v1/namespaces/pulse-system/pods";
    private static final String EVENTS_PATH = "/api/v1/namespaces/pulse-system/events";

    private MockRestServiceServer server;
    private HttpKubernetesClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        RestClient rest = builder.baseUrl("http://kube.test").build();
        client = new HttpKubernetesClient(timeout -> rest, "http://kube.test", 500, 5000);
    }

    private ResponseActions expectPath(String path) {
        return server.expect(request -> assertEquals(path, request.getURI().getPath()));
    }

    private static Deadline freshDeadline() {
        return Deadline.afterMillis(5000);
    }

    private static String statusBody(String reason, String message) {
        return "{\"kind\":\"Status\",\"status\":\"Failure\",\"reason\":\"" + reason
                + "\",\"message\":\"" + message + "\"}";
    }

    // --- 1–3: successful parses (acceptance rows) ---------------------------

    @Test
    void parsesDeploymentListWithIdentityFields() {
        String json = """
                {"apiVersion":"apps/v1","kind":"DeploymentList",
                 "metadata":{"resourceVersion":"9001"},
                 "items":[{"metadata":{
                     "uid":"uid-1","name":"pulse-api","namespace":"pulse-system",
                     "resourceVersion":"100","generation":7,
                     "creationTimestamp":"2026-11-13T00:00:00Z",
                     "annotations":{"deployment.kubernetes.io/revision":"3"}},
                   "spec":{"replicas":2,
                     "selector":{"matchLabels":{"app.kubernetes.io/name":"pulse-api"}},
                     "template":{"spec":{"containers":[{"image":"pulse-api:1.0.1"}]}}},
                   "status":{"observedGeneration":7,"readyReplicas":2,"availableReplicas":2,
                     "updatedReplicas":2,
                     "conditions":[{"type":"Available","status":"True",
                       "reason":"MinimumReplicasAvailable","message":"all good"}]}}]}""";
        expectPath(DEPLOYMENTS_PATH).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        List<DeploymentInfo> deployments = client.listDeployments(NS, freshDeadline());

        assertEquals(1, deployments.size());
        DeploymentInfo d = deployments.get(0);
        assertEquals("uid-1", d.uid());
        assertEquals("pulse-api", d.name());
        assertEquals("pulse-system", d.namespace());
        assertEquals("100", d.resourceVersion());
        assertEquals(7L, d.generation());
        assertEquals(7L, d.observedGeneration());
        assertEquals(3, d.revision());
        assertEquals("pulse-api:1.0.1", d.image());
        assertEquals(2, d.replicasDesired());
        assertEquals(2, d.replicasReady());
        assertEquals(2, d.replicasAvailable());
        assertEquals(2, d.replicasUpdated());
        assertEquals("pulse-api", d.podSelector().get("app.kubernetes.io/name"));
        assertEquals(1, d.conditions().size());
        assertEquals("Available", d.conditions().get(0).type());
        server.verify();
    }

    @Test
    void parsesPodListWithSelectorQuery() {
        String json = """
                {"kind":"PodList","items":[
                  {"metadata":{"uid":"p-1","name":"pulse-api-abc","namespace":"pulse-system",
                     "resourceVersion":"55",
                     "ownerReferences":[{"name":"pulse-api-7d9f"}]},
                   "spec":{"nodeName":"node-1"},
                   "status":{"phase":"Running","containerStatuses":[
                     {"ready":true,"restartCount":0,"state":{}}]}}]}""";
        expectPath(PODS_PATH)
                .andExpect(request -> {
                    assertTrue(request.getURI().getRawQuery().contains("limit=500"),
                            "query was: " + request.getURI().getRawQuery());
                    assertTrue(request.getURI().getQuery()
                            .contains("labelSelector=app.kubernetes.io/name=pulse-api"),
                            "query was: " + request.getURI().getQuery());
                })
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        List<PodInfo> pods = client.listPods(NS, "app.kubernetes.io/name=pulse-api", freshDeadline());

        assertEquals(1, pods.size());
        PodInfo p = pods.get(0);
        assertEquals("p-1", p.uid());
        assertEquals("pulse-api-abc", p.name());
        assertEquals("Running", p.phase());
        assertTrue(p.ready());
        assertEquals(0, p.restarts());
        assertNull(p.waitingReason());
        assertEquals("node-1", p.nodeName());
        assertEquals("pulse-api-7d9f", p.ownerName());
        server.verify();
    }

    @Test
    void parsesPodWaitingReasonAndRestarts() {
        String json = """
                {"kind":"PodList","items":[
                  {"metadata":{"uid":"p-2","name":"todoapp-xyz","namespace":"pulse-system"},
                   "spec":{},
                   "status":{"phase":"CrashLoopBackOff","containerStatuses":[
                     {"ready":false,"restartCount":4,
                      "state":{"waiting":{"reason":"CrashLoopBackOff"}}}]}}]}""";
        expectPath(PODS_PATH).andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        List<PodInfo> pods = client.listPods(NS, null, freshDeadline());

        assertEquals(1, pods.size());
        assertFalse(pods.get(0).ready());
        assertEquals(4, pods.get(0).restarts());
        assertEquals("CrashLoopBackOff", pods.get(0).waitingReason());
        server.verify();
    }

    @Test
    void parsesEventListWithFieldSelector() {
        String json = """
                {"kind":"EventList","items":[
                  {"metadata":{"uid":"e-1","creationTimestamp":"2026-11-13T01:00:00Z"},
                   "type":"Warning","reason":"BackOff",
                   "message":"Back-off restarting failed container",
                   "count":3,
                   "firstTimestamp":"2026-11-13T01:00:00Z",
                   "lastTimestamp":"2026-11-13T01:05:00Z",
                   "involvedObject":{"kind":"Pod","name":"todoapp-xyz"}}]}""";
        expectPath(EVENTS_PATH)
                .andExpect(request -> assertTrue(
                        request.getURI().getQuery().contains("fieldSelector=involvedObject.name"),
                        "query was: " + request.getURI().getQuery()))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));

        List<EventInfo> events = client.listEvents(NS, "involvedObject.name=todoapp-xyz", freshDeadline());

        assertEquals(1, events.size());
        EventInfo e = events.get(0);
        assertEquals("e-1", e.uid());
        assertEquals("Warning", e.type());
        assertEquals("BackOff", e.reason());
        assertEquals("Back-off restarting failed container", e.message());
        assertEquals(3, e.count());
        assertEquals("2026-11-13T01:05:00Z", e.lastTimestamp());
        assertEquals("Pod", e.involvedKind());
        assertEquals("todoapp-xyz", e.involvedName());
        server.verify();
    }

    // --- 4–7: error taxonomy (acceptance rows) ------------------------------

    @Test
    void connectionRefusedBecomesUnavailable() {
        RestClient refused = RestClient.builder().baseUrl("http://127.0.0.1:9").build();
        HttpKubernetesClient offline = new HttpKubernetesClient(timeout -> refused, "http://127.0.0.1:9", 500, 5000);

        KubernetesException ex = assertThrows(KubernetesException.class,
                () -> offline.listDeployments(NS, freshDeadline()));

        assertEquals(KubernetesException.Kind.UNAVAILABLE, ex.getKind());
        assertTrue(ex.getMessage().contains("unreachable"), ex.getMessage());
    }

    @Test
    void upstream403BecomesForbiddenWithSanitizedDetail() {
        String body = statusBody("Forbidden",
                "deployments \\\"pulse-api\\\" is forbidden: User \\\"system:serviceaccount:pulse:pulse-observer\\\" cannot list");
        expectPath(DEPLOYMENTS_PATH)
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body));

        KubernetesException ex = assertThrows(KubernetesException.class,
                () -> client.listDeployments(NS, freshDeadline()));

        assertEquals(KubernetesException.Kind.FORBIDDEN, ex.getKind());
        assertTrue(ex.getMessage().contains("Kubernetes HTTP 403"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Forbidden"), ex.getMessage());
        assertTrue(ex.getMessage().contains("cannot list"), ex.getMessage());
        server.verify();
    }

    @Test
    void upstream404BecomesNotFound() {
        expectPath(DEPLOYMENTS_PATH)
                .andRespond(withStatus(HttpStatus.NOT_FOUND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(statusBody("NotFound", "namespaces \\\"ghost\\\" not found")));

        KubernetesException ex = assertThrows(KubernetesException.class,
                () -> client.listDeployments(NS, freshDeadline()));

        assertEquals(KubernetesException.Kind.NOT_FOUND, ex.getKind());
        assertTrue(ex.getMessage().contains("NotFound"), ex.getMessage());
        server.verify();
    }

    @Test
    void malformedJsonBecomesBadResponse() {
        expectPath(DEPLOYMENTS_PATH)
                .andRespond(withSuccess("{this is not json", MediaType.APPLICATION_JSON));

        KubernetesException ex = assertThrows(KubernetesException.class,
                () -> client.listDeployments(NS, freshDeadline()));

        assertEquals(KubernetesException.Kind.BAD_RESPONSE, ex.getKind());
        server.verify();
    }

    // --- 8: pagination (acceptance row) -------------------------------------

    @Test
    void paginationFollowsContinuationTokensAndMerges() {
        String page1 = """
                {"kind":"DeploymentList",
                 "metadata":{"continue":"tok-1"},
                 "items":[{"metadata":{"uid":"d1","name":"a","namespace":"pulse-system"},"spec":{},"status":{}},
                          {"metadata":{"uid":"d2","name":"b","namespace":"pulse-system"},"spec":{},"status":{}}]}""";
        String page2 = """
                {"kind":"DeploymentList",
                 "metadata":{"resourceVersion":"9","continue":""},
                 "items":[{"metadata":{"uid":"d3","name":"c","namespace":"pulse-system"},"spec":{},"status":{}}]}""";

        expectPath(DEPLOYMENTS_PATH)
                .andExpect(request -> assertTrue(request.getURI().getRawQuery().contains("limit=500")))
                .andRespond(withSuccess(page1, MediaType.APPLICATION_JSON));
        expectPath(DEPLOYMENTS_PATH)
                .andExpect(request -> {
                    String query = request.getURI().getRawQuery();
                    assertTrue(query.contains("limit=500"), query);
                    assertTrue(query.contains("continue=tok-1"), query);
                })
                .andRespond(withSuccess(page2, MediaType.APPLICATION_JSON));

        List<DeploymentInfo> deployments = client.listDeployments(NS, freshDeadline());

        assertEquals(3, deployments.size());
        assertEquals("d1", deployments.get(0).uid());
        assertEquals("d3", deployments.get(2).uid());
        server.verify();
    }

    @Test
    void paginationRestartsOnceWhenContinuationTokenExpires() {
        String page1 = """
                {"kind":"PodList","metadata":{"continue":"tok-1"},
                 "items":[{"metadata":{"uid":"p1","name":"a"},"spec":{},"status":{}},
                          {"metadata":{"uid":"p2","name":"b"},"spec":{},"status":{}}]}""";
        String restarted = """
                {"kind":"PodList","metadata":{},
                 "items":[{"metadata":{"uid":"p9","name":"only"},"spec":{},"status":{}}]}""";

        expectPath(PODS_PATH).andRespond(withSuccess(page1, MediaType.APPLICATION_JSON));
        expectPath(PODS_PATH)
                .andRespond(withStatus(HttpStatus.GONE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(statusBody("Expired", "The provided continue parameter is too old")));
        expectPath(PODS_PATH).andRespond(withSuccess(restarted, MediaType.APPLICATION_JSON));

        List<PodInfo> pods = client.listPods(NS, null, freshDeadline());

        assertEquals(1, pods.size());
        assertEquals("p9", pods.get(0).uid());
        server.verify();
    }

    @Test
    void paginationSecondExpiredTokenFailsInsteadOfTruncating() {
        String page1 = """
                {"kind":"PodList","metadata":{"continue":"tok-1"},"items":[]}""";
        String page2 = """
                {"kind":"PodList","metadata":{"continue":"tok-2"},"items":[]}""";

        expectPath(PODS_PATH).andRespond(withSuccess(page1, MediaType.APPLICATION_JSON));
        expectPath(PODS_PATH)
                .andRespond(withStatus(HttpStatus.GONE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(statusBody("Expired", "too old")));
        expectPath(PODS_PATH).andRespond(withSuccess(page2, MediaType.APPLICATION_JSON));
        expectPath(PODS_PATH)
                .andRespond(withStatus(HttpStatus.GONE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(statusBody("Expired", "too old again")));

        KubernetesException ex = assertThrows(KubernetesException.class,
                () -> client.listPods(NS, null, freshDeadline()));

        assertEquals(KubernetesException.Kind.BAD_RESPONSE, ex.getKind());
        assertTrue(ex.getMessage().contains("continuation token"), ex.getMessage());
        server.verify();
    }

    // --- 11–13: remaining upstream statuses ---------------------------------

    @Test
    void upstream401BecomesUnauthenticated() {
        expectPath(DEPLOYMENTS_PATH)
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(statusBody("Unauthorized", "the server has asked for the client to provide credentials")));

        KubernetesException ex = assertThrows(KubernetesException.class,
                () -> client.listDeployments(NS, freshDeadline()));

        assertEquals(KubernetesException.Kind.UNAUTHENTICATED, ex.getKind());
        assertTrue(ex.getMessage().contains("Kubernetes HTTP 401"), ex.getMessage());
        server.verify();
    }

    @Test
    void upstream429BecomesThrottledWithRetryAfter() {
        expectPath(EVENTS_PATH)
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(statusBody("TooManyRequests", "too many requests")));

        KubernetesException ex = assertThrows(KubernetesException.class,
                () -> client.listEvents(NS, null, freshDeadline()));

        assertEquals(KubernetesException.Kind.THROTTLED, ex.getKind());
        assertTrue(ex.getMessage().contains("Retry-After: 7"), ex.getMessage());
        server.verify();
    }

    @Test
    void upstream500BecomesUpstreamError() {
        expectPath(DEPLOYMENTS_PATH)
                .andRespond(withServerError());

        KubernetesException ex = assertThrows(KubernetesException.class,
                () -> client.listDeployments(NS, freshDeadline()));

        assertEquals(KubernetesException.Kind.UPSTREAM_ERROR, ex.getKind());
        server.verify();
    }

    // --- 9: deadline (adjustment 3) ----------------------------------------

    @Test
    void expiredDeadlineFailsBeforeAnyHttpRequest() {
        AtomicBoolean called = new AtomicBoolean(false);
        HttpKubernetesClient gated = new HttpKubernetesClient(timeout -> {
            called.set(true);
            return RestClient.builder().baseUrl("http://kube.test").build();
        }, "http://kube.test", 500, 5000);

        KubernetesException ex = assertThrows(KubernetesException.class,
                () -> gated.listDeployments(NS, new Deadline(System.nanoTime() - 1)));

        assertEquals(KubernetesException.Kind.DEADLINE_EXCEEDED, ex.getKind());
        assertFalse(called.get(), "no HTTP request may be made once the deadline has passed");
    }

    @Test
    void readTimeoutIsClampedToRemainingDeadline() {
        List<Long> requestedTimeouts = new ArrayList<>();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer clampedServer = MockRestServiceServer.bindTo(builder).build();
        RestClient rest = builder.baseUrl("http://kube.test").build();
        clampedServer.expect(request -> assertEquals(DEPLOYMENTS_PATH, request.getURI().getPath()))
                .andRespond(withSuccess("{\"kind\":\"DeploymentList\",\"items\":[]}",
                        MediaType.APPLICATION_JSON));
        HttpKubernetesClient clamped = new HttpKubernetesClient(timeout -> {
            requestedTimeouts.add(timeout);
            return rest;
        }, "http://kube.test", 500, 5000);

        clamped.listDeployments(NS, new Deadline(System.nanoTime() + 500_000_000L));

        assertEquals(1, requestedTimeouts.size());
        assertTrue(requestedTimeouts.get(0) >= 1, "got " + requestedTimeouts.get(0));
        assertTrue(requestedTimeouts.get(0) <= 500,
                "read timeout must be clamped to the ~500ms remaining budget, got " + requestedTimeouts.get(0));
        clampedServer.verify();
    }

    // --- 10: disabled (adjustment 1) ---------------------------------------

    @Test
    void disabledClientStartsCleanAndFailsOnUseWithoutTouchingNetwork() {
        PulseProperties props = new PulseProperties(); // enabled defaults to false
        HttpKubernetesClient disabled = new HttpKubernetesClient(props); // no credential files required

        KubernetesException ex = assertThrows(KubernetesException.class,
                () -> disabled.listDeployments(NS, freshDeadline()));

        assertEquals(KubernetesException.Kind.DISABLED, ex.getKind());
        assertTrue(ex.getMessage().contains("enabled=false"), ex.getMessage());
    }

    // --- auth header + structural honesty -----------------------------------

    @Test
    void everyRequestCarriesBearerTokenHeader() {
        expectPath(DEPLOYMENTS_PATH)
                .andExpect(header("Authorization", "Bearer test-token"))
                .andRespond(withSuccess("{\"kind\":\"DeploymentList\",\"items\":[]}",
                        MediaType.APPLICATION_JSON));

        client.listDeployments(NS, freshDeadline());

        server.verify();
    }

    @Test
    void responseWithoutItemsArrayIsBadResponse() {
        expectPath(DEPLOYMENTS_PATH)
                .andRespond(withSuccess("{\"kind\":\"DeploymentList\"}", MediaType.APPLICATION_JSON));

        KubernetesException ex = assertThrows(KubernetesException.class,
                () -> client.listDeployments(NS, freshDeadline()));

        assertEquals(KubernetesException.Kind.BAD_RESPONSE, ex.getKind());
        assertTrue(ex.getMessage().contains("items"), ex.getMessage());
        server.verify();
    }

    @Test
    void errorDetailIsTruncatedTo300Chars() {
        StringBuilder longMessage = new StringBuilder();
        for (int i = 0; i < 400; i++) {
            longMessage.append('x');
        }
        expectPath(DEPLOYMENTS_PATH)
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(statusBody("InternalError", longMessage.toString())));

        KubernetesException ex = assertThrows(KubernetesException.class,
                () -> client.listDeployments(NS, freshDeadline()));

        assertTrue(ex.getMessage().length() <= 340,
                "detail must be truncated, got length " + ex.getMessage().length());
        assertTrue(ex.getMessage().endsWith("..."), ex.getMessage());
        server.verify();
    }
}
