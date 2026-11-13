package com.pulse.service;

import com.pulse.config.PulseProperties;
import com.pulse.kubernetes.Deadline;
import com.pulse.kubernetes.KubernetesClient;
import com.pulse.kubernetes.KubernetesClient.DeploymentInfo;
import com.pulse.kubernetes.KubernetesClient.EventInfo;
import com.pulse.kubernetes.KubernetesClient.PodInfo;
import com.pulse.kubernetes.KubernetesException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reconciles one Deployment + its Pods + relevant Events into a single honest
 * {@link ClusterSnapshot}. One {@link Deadline} is created per observation and
 * shared by all three reads. Rules:
 * - missing deployment → NOT_FOUND (404 later)
 * - pods/deployments read failure → propagates (403/503 later)
 * - events read failure → snapshot still produced, marked complete=false with a note
 *   (partial evidence is reported as partial, never as full health)
 * - abnormal states always carry notes; READY with no evidence gaps carries none
 */
@Service
public class ClusterObservationService {

    static final String STATE_READY = "READY";
    static final String STATE_DEGRADED = "DEGRADED";
    static final String STATE_PROGRESSING = "PROGRESSING";
    static final String STATE_SUSPENDED = "SUSPENDED";

    /** Waiting reasons that mean the pod is unhealthy, not merely starting. */
    private static final Set<String> CRASH_REASONS = Set.of(
            "CrashLoopBackOff", "ImagePullBackOff", "ErrImagePull",
            "CreateContainerConfigError", "CreateContainerError",
            "RunContainerError", "InvalidImageName");

    private final PulseProperties properties;
    private final KubernetesClient kubernetes;

    public ClusterObservationService(PulseProperties properties, KubernetesClient kubernetes) {
        this.properties = properties;
        this.kubernetes = kubernetes;
    }

    public record ReplicaStatus(int desired, int ready, int available, int updated) {
    }

    /** List-row view: deployment-level evidence only — no pod/event claims at list level. */
    public record DeploymentSummary(
            String name,
            String namespace,
            String uid,
            Integer revision,
            String image,
            long generation,
            long observedGeneration,
            ReplicaStatus replicas,
            String creationTimestamp) {
    }

    public record ClusterSnapshot(
            String deployment,
            String namespace,
            String observedAt,
            String observedState,
            Integer revision,
            String image,
            long generation,
            long observedGeneration,
            ReplicaStatus replicas,
            List<PodInfo> pods,
            List<EventInfo> events,
            boolean complete,
            List<String> notes) {
    }

    /**
     * Lists deployments in the configured namespace. Deliberately does NOT read
     * pods/events per row (N+1) — the list makes no claim beyond what the
     * Deployment objects themselves report.
     */
    public List<DeploymentSummary> deploymentList() {
        PulseProperties.Kubernetes cfg = properties.getKubernetes();
        String namespace = cfg.getNamespace();
        Deadline deadline = Deadline.afterMillis(cfg.getDeadlineMs());
        return kubernetes.listDeployments(namespace, deadline).stream()
                .map(d -> new DeploymentSummary(
                        d.name(),
                        d.namespace(),
                        d.uid(),
                        d.revision(),
                        d.image(),
                        d.generation(),
                        d.observedGeneration(),
                        new ReplicaStatus(d.replicasDesired(), d.replicasReady(),
                                d.replicasAvailable(), d.replicasUpdated()),
                        d.creationTimestamp()))
                .toList();
    }

    public ClusterSnapshot snapshot(String deploymentName) {
        PulseProperties.Kubernetes cfg = properties.getKubernetes();
        String namespace = cfg.getNamespace();
        Deadline deadline = Deadline.afterMillis(cfg.getDeadlineMs());

        List<DeploymentInfo> deployments = kubernetes.listDeployments(namespace, deadline);
        DeploymentInfo deployment = deployments.stream()
                .filter(d -> deploymentName.equals(d.name()))
                .findFirst()
                .orElseThrow(() -> KubernetesException.notFound(
                        "deployment '" + deploymentName + "' not found in namespace '" + namespace + "'"));

        String selector = selectorString(deployment.podSelector());
        List<PodInfo> pods = kubernetes.listPods(namespace, selector.isEmpty() ? null : selector, deadline);

        List<String> notes = new ArrayList<>();
        List<EventInfo> rawEvents;
        boolean complete = true;
        try {
            rawEvents = kubernetes.listEvents(namespace, null, deadline);
        } catch (KubernetesException e) {
            // Secondary evidence: never fail the whole observation, never hide the gap.
            rawEvents = List.of();
            complete = false;
            notes.add("events unavailable: " + e.getKind() + " — " + e.getMessage());
        }

        String state = computeState(deployment, pods, notes);

        if (pods.isEmpty() && deployment.replicasDesired() > 0) {
            notes.add("no pods matched selector '" + selector + "'");
        }

        List<EventInfo> events = relevantEvents(rawEvents, deployment, pods);
        long warnings = events.stream().filter(e -> "Warning".equalsIgnoreCase(e.type())).count();
        if (warnings > 0) {
            String latest = events.stream()
                    .filter(e -> "Warning".equalsIgnoreCase(e.type()))
                    .map(EventInfo::reason)
                    .filter(r -> r != null)
                    .findFirst()
                    .orElse(null);
            notes.add(warnings + " relevant warning event(s)"
                    + (latest != null ? ", latest: " + latest : ""));
        }
        if (events.isEmpty() && complete && STATE_DEGRADED.equals(state)) {
            notes.add("no cluster events found to explain the degraded state (evidence gap)");
        }

        return new ClusterSnapshot(
                deploymentName,
                namespace,
                Instant.now().toString(),
                state,
                deployment.revision(),
                deployment.image(),
                deployment.generation(),
                deployment.observedGeneration(),
                new ReplicaStatus(
                        deployment.replicasDesired(),
                        deployment.replicasReady(),
                        deployment.replicasAvailable(),
                        deployment.replicasUpdated()),
                List.copyOf(pods),
                events,
                complete,
                List.copyOf(notes));
    }

    private static String computeState(DeploymentInfo deployment, List<PodInfo> pods, List<String> notes) {
        String state;
        if (deployment.replicasDesired() == 0) {
            state = STATE_SUSPENDED;
            notes.add("scaled to zero — no pods expected");
        } else if (deployment.replicasReady() < deployment.replicasDesired()
                || deployment.replicasAvailable() < deployment.replicasDesired()) {
            state = STATE_DEGRADED;
            notes.add("only " + deployment.replicasReady() + "/" + deployment.replicasDesired()
                    + " replicas ready (" + deployment.replicasAvailable() + " available)");
        } else if (deployment.observedGeneration() < deployment.generation()) {
            state = STATE_PROGRESSING;
            notes.add("spec change not yet fully observed: generation " + deployment.generation()
                    + " vs observedGeneration " + deployment.observedGeneration());
        } else if (deployment.replicasUpdated() < deployment.replicasDesired()) {
            state = STATE_PROGRESSING;
            notes.add("rollout in progress: " + deployment.replicasUpdated() + "/"
                    + deployment.replicasDesired() + " replicas updated");
        } else {
            state = STATE_READY;
        }

        // Pod evidence overrides optimistic Deployment status: never report READY
        // while a container is crash-looping (status can lag the kubelet).
        if (!STATE_SUSPENDED.equals(state)) {
            for (PodInfo pod : pods) {
                if (pod.waitingReason() != null && CRASH_REASONS.contains(pod.waitingReason())) {
                    state = STATE_DEGRADED;
                    notes.add("pod " + pod.name() + ": " + pod.waitingReason()
                            + (pod.restarts() > 0 ? " (" + pod.restarts() + " restarts)" : ""));
                } else if (!pod.ready()) {
                    notes.add("pod " + pod.name() + " reports ready=false (phase "
                            + pod.phase() + ")");
                }
            }
        }
        return state;
    }

    private static List<EventInfo> relevantEvents(List<EventInfo> events, DeploymentInfo deployment,
                                                  List<PodInfo> pods) {
        Set<String> relevantNames = new HashSet<>();
        relevantNames.add(deployment.name());
        for (PodInfo pod : pods) {
            relevantNames.add(pod.name());
            if (pod.ownerName() != null) {
                relevantNames.add(pod.ownerName());
            }
        }
        return events.stream()
                .filter(e -> e.involvedName() != null && relevantNames.contains(e.involvedName()))
                .sorted(Comparator.comparing(EventInfo::lastTimestamp,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private static String selectorString(Map<String, String> selector) {
        if (selector == null || selector.isEmpty()) {
            return "";
        }
        return selector.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
    }
}
