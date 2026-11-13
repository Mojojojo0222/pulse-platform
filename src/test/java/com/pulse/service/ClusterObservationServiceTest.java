package com.pulse.service;

import com.pulse.config.PulseProperties;
import com.pulse.kubernetes.Deadline;
import com.pulse.kubernetes.KubernetesClient;
import com.pulse.kubernetes.KubernetesClient.DeploymentInfo;
import com.pulse.kubernetes.KubernetesClient.EventInfo;
import com.pulse.kubernetes.KubernetesClient.PodInfo;
import com.pulse.kubernetes.KubernetesException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClusterObservationServiceTest {

    private static final String NS = "pulse-system";

    private FakeKubernetes fake;
    private ClusterObservationService service;

    @BeforeEach
    void setUp() {
        fake = new FakeKubernetes();
        service = new ClusterObservationService(new PulseProperties(), fake);
    }

    // --- fixtures -----------------------------------------------------------

    private static DeploymentInfo deployment(String name, int desired, int ready, int available,
                                             int updated, long generation, long observedGeneration) {
        return new DeploymentInfo("uid-1", name, NS, "rv-1", generation, observedGeneration, 7,
                "todoapp:v1", desired, ready, available, updated, "2026-11-13T00:00:00Z",
                Map.of("app", name), List.of());
    }

    private static PodInfo pod(String name, boolean ready, int restarts, String waitingReason, String owner) {
        return new PodInfo("p-uid-" + name, name, NS, "rv", "Running", ready, restarts,
                waitingReason, "node-1", "2026-11-13T00:00:00Z", owner);
    }

    private static EventInfo event(String involvedName, String type, String reason, String timestamp) {
        return new EventInfo("e-uid-" + involvedName + timestamp, type, reason, "message",
                1, timestamp, timestamp, "Pod", involvedName);
    }

    // --- state machine ------------------------------------------------------

    @Test
    void healthyDeploymentIsReadyWithSharedDeadlineAcrossAllThreeReads() {
        fake.deployments.add(deployment("todoapp", 2, 2, 2, 2, 5, 5));
        fake.pods.add(pod("todoapp-a", true, 0, null, "todoapp-6d7"));
        fake.pods.add(pod("todoapp-b", true, 0, null, "todoapp-6d7"));

        ClusterObservationService.ClusterSnapshot snapshot = service.snapshot("todoapp");

        assertEquals("todoapp", snapshot.deployment());
        assertEquals(NS, snapshot.namespace());
        assertEquals(ClusterObservationService.STATE_READY, snapshot.observedState());
        assertTrue(snapshot.complete());
        assertTrue(snapshot.notes().isEmpty(), "healthy observation must carry no notes: " + snapshot.notes());
        assertNotNull(snapshot.observedAt());
        assertEquals(7, snapshot.revision());
        assertEquals("todoapp:v1", snapshot.image());
        assertEquals(5, snapshot.generation());
        assertEquals(5, snapshot.observedGeneration());
        assertEquals(new ClusterObservationService.ReplicaStatus(2, 2, 2, 2), snapshot.replicas());
        assertEquals(2, snapshot.pods().size());
        assertTrue(snapshot.events().isEmpty());

        // One Deadline per observation, reused by all three calls (adjustment 3 wiring).
        assertEquals(3, fake.deadlines.size());
        assertSame(fake.deadlines.get(0), fake.deadlines.get(1));
        assertSame(fake.deadlines.get(0), fake.deadlines.get(2));
        assertEquals("app=todoapp", fake.lastPodSelector);
        assertEquals(List.of(NS, NS, NS), fake.namespaces);
    }

    @Test
    void readyBelowDesiredIsDegraded() {
        fake.deployments.add(deployment("todoapp", 3, 2, 2, 3, 1, 1));
        fake.pods.add(pod("todoapp-a", true, 0, null, "rs"));

        ClusterObservationService.ClusterSnapshot snapshot = service.snapshot("todoapp");

        assertEquals(ClusterObservationService.STATE_DEGRADED, snapshot.observedState());
        assertTrue(snapshot.notes().stream().anyMatch(n -> n.contains("2/3 replicas ready")),
                snapshot.notes().toString());
    }

    @Test
    void partialRolloutIsProgressing() {
        fake.deployments.add(deployment("todoapp", 3, 3, 3, 1, 4, 4));
        fake.pods.add(pod("todoapp-a", true, 0, null, "rs"));

        ClusterObservationService.ClusterSnapshot snapshot = service.snapshot("todoapp");

        assertEquals(ClusterObservationService.STATE_PROGRESSING, snapshot.observedState());
        assertTrue(snapshot.notes().stream().anyMatch(n -> n.contains("rollout in progress: 1/3")),
                snapshot.notes().toString());
    }

    @Test
    void unobservedGenerationIsProgressing() {
        fake.deployments.add(deployment("todoapp", 2, 2, 2, 2, 9, 8));
        fake.pods.add(pod("todoapp-a", true, 0, null, "rs"));

        ClusterObservationService.ClusterSnapshot snapshot = service.snapshot("todoapp");

        assertEquals(ClusterObservationService.STATE_PROGRESSING, snapshot.observedState());
        assertTrue(snapshot.notes().stream().anyMatch(n -> n.contains("generation 9")),
                snapshot.notes().toString());
    }

    @Test
    void crashLoopingPodDowngradesOptimisticStatusToDegraded() {
        fake.deployments.add(deployment("todoapp", 2, 2, 2, 2, 5, 5));
        fake.pods.add(pod("todoapp-a", true, 0, null, "rs"));
        fake.pods.add(pod("todoapp-b", false, 4, "CrashLoopBackOff", "rs"));

        ClusterObservationService.ClusterSnapshot snapshot = service.snapshot("todoapp");

        assertEquals(ClusterObservationService.STATE_DEGRADED, snapshot.observedState());
        assertTrue(snapshot.notes().stream().anyMatch(n ->
                        n.contains("todoapp-b") && n.contains("CrashLoopBackOff") && n.contains("4 restarts")),
                snapshot.notes().toString());
    }

    @Test
    void scaledToZeroIsSuspendedNotReady() {
        fake.deployments.add(deployment("todoapp", 0, 0, 0, 0, 3, 3));

        ClusterObservationService.ClusterSnapshot snapshot = service.snapshot("todoapp");

        assertEquals(ClusterObservationService.STATE_SUSPENDED, snapshot.observedState());
        assertTrue(snapshot.notes().stream().anyMatch(n -> n.contains("scaled to zero")),
                snapshot.notes().toString());
    }

    @Test
    void unknownDeploymentThrowsNotFound() {
        KubernetesException ex = assertThrows(KubernetesException.class,
                () -> service.snapshot("ghost"));

        assertEquals(KubernetesException.Kind.NOT_FOUND, ex.getKind());
        assertTrue(ex.getMessage().contains("'ghost'"), ex.getMessage());
        assertTrue(ex.getMessage().contains(NS), ex.getMessage());
    }

    // --- partial evidence (never fake completeness) -------------------------

    @Test
    void forbiddenEventsStillReturnSnapshotMarkedIncomplete() {
        fake.deployments.add(deployment("todoapp", 2, 2, 2, 2, 5, 5));
        fake.pods.add(pod("todoapp-a", true, 0, null, "rs"));
        fake.eventsFailure = KubernetesException.forbidden("Kubernetes HTTP 403 for events");

        ClusterObservationService.ClusterSnapshot snapshot = service.snapshot("todoapp");

        assertFalse(snapshot.complete(), "denied events must mark the observation incomplete");
        assertTrue(snapshot.events().isEmpty());
        assertEquals(ClusterObservationService.STATE_READY, snapshot.observedState());
        assertTrue(snapshot.notes().stream().anyMatch(n ->
                        n.contains("events unavailable") && n.contains("FORBIDDEN")),
                snapshot.notes().toString());
    }

    @Test
    void degradedWithNoEventsNotesTheEvidenceGap() {
        fake.deployments.add(deployment("todoapp", 3, 1, 1, 3, 1, 1));
        fake.pods.add(pod("todoapp-a", true, 0, null, "rs"));

        ClusterObservationService.ClusterSnapshot snapshot = service.snapshot("todoapp");

        assertEquals(ClusterObservationService.STATE_DEGRADED, snapshot.observedState());
        assertTrue(snapshot.complete());
        assertTrue(snapshot.notes().stream().anyMatch(n -> n.contains("evidence gap")),
                snapshot.notes().toString());
    }

    @Test
    void eventsAreFilteredToDeploymentAndItsPodsAndSortedNewestFirst() {
        fake.deployments.add(deployment("todoapp", 1, 1, 1, 1, 1, 1));
        fake.pods.add(pod("todoapp-a", true, 0, null, "todoapp-6d7"));
        fake.events.add(event("unrelated-app", "Warning", "BackOff", "2026-11-13T03:00:00Z"));
        fake.events.add(event("todoapp-a", "Warning", "BackOff", "2026-11-13T01:00:00Z"));
        fake.events.add(event("todoapp-6d7", "Normal", "ScalingReplicaSet", "2026-11-13T02:00:00Z"));
        fake.events.add(event("todoapp", "Normal", "ScalingReplicaSet", "2026-11-13T04:00:00Z"));

        ClusterObservationService.ClusterSnapshot snapshot = service.snapshot("todoapp");

        assertEquals(3, snapshot.events().size(), "unrelated-app event must be filtered out");
        assertEquals("todoapp", snapshot.events().get(0).involvedName());
        assertEquals("todoapp-6d7", snapshot.events().get(1).involvedName());
        assertEquals("todoapp-a", snapshot.events().get(2).involvedName());
        assertTrue(snapshot.notes().stream().anyMatch(n -> n.contains("1 relevant warning event(s)")
                        && n.contains("latest: BackOff")),
                snapshot.notes().toString());
    }

    // --- deployment list ----------------------------------------------------

    @Test
    void deploymentListMapsSummariesWithoutPodOrEventReads() {
        fake.deployments.add(deployment("todoapp", 2, 2, 2, 2, 5, 5));

        List<ClusterObservationService.DeploymentSummary> list = service.deploymentList();

        assertEquals(1, list.size());
        ClusterObservationService.DeploymentSummary row = list.get(0);
        assertEquals("todoapp", row.name());
        assertEquals(NS, row.namespace());
        assertEquals("uid-1", row.uid());
        assertEquals(7, row.revision());
        assertEquals("todoapp:v1", row.image());
        assertEquals(new ClusterObservationService.ReplicaStatus(2, 2, 2, 2), row.replicas());
        assertEquals(1, fake.deadlines.size(), "list = one call, one deadline");
        assertTrue(fake.namespaces.contains(NS));
    }

    @Test
    void deploymentListPropagatesClientFailure() {
        fake.deploymentsFailure = KubernetesException.unavailable("Kubernetes unreachable (refused)", null);

        KubernetesException ex = assertThrows(KubernetesException.class, () -> service.deploymentList());
        assertEquals(KubernetesException.Kind.UNAVAILABLE, ex.getKind());
    }

    // --- fake ---------------------------------------------------------------

    private static final class FakeKubernetes implements KubernetesClient {
        List<DeploymentInfo> deployments = new ArrayList<>();
        List<PodInfo> pods = new ArrayList<>();
        List<EventInfo> events = new ArrayList<>();
        RuntimeException deploymentsFailure;
        RuntimeException podsFailure;
        RuntimeException eventsFailure;
        final List<Deadline> deadlines = new ArrayList<>();
        final List<String> namespaces = new ArrayList<>();
        String lastPodSelector;

        @Override
        public List<DeploymentInfo> listDeployments(String namespace, Deadline deadline) {
            namespaces.add(namespace);
            deadlines.add(deadline);
            if (deploymentsFailure != null) {
                throw deploymentsFailure;
            }
            return List.copyOf(deployments);
        }

        @Override
        public List<PodInfo> listPods(String namespace, String labelSelector, Deadline deadline) {
            namespaces.add(namespace);
            deadlines.add(deadline);
            lastPodSelector = labelSelector;
            if (podsFailure != null) {
                throw podsFailure;
            }
            return List.copyOf(pods);
        }

        @Override
        public List<EventInfo> listEvents(String namespace, String fieldSelector, Deadline deadline) {
            namespaces.add(namespace);
            deadlines.add(deadline);
            if (eventsFailure != null) {
                throw eventsFailure;
            }
            return List.copyOf(events);
        }
    }
}
