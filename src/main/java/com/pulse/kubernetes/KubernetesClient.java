package com.pulse.kubernetes;

import java.util.List;
import java.util.Map;

/**
 * The three read operations Pulse needs from the Kubernetes API server —
 * deliberately NOT a generic SDK. Implementations are synchronous, read-only,
 * paginated, and bounded by the {@link Deadline} passed to each call.
 * The deadline spans one observation: the caller creates it once and reuses it
 * across all three methods so sequential calls cannot accumulate unbounded latency.
 */
public interface KubernetesClient {

    List<DeploymentInfo> listDeployments(String namespace, Deadline deadline);

    List<PodInfo> listPods(String namespace, String labelSelector, Deadline deadline);

    List<EventInfo> listEvents(String namespace, String fieldSelector, Deadline deadline);

    /** Deployment identity + rollout state. Null fields mean "API did not report it", never a guess. */
    record DeploymentInfo(
            String uid,
            String name,
            String namespace,
            String resourceVersion,
            long generation,
            long observedGeneration,
            Integer revision,
            String image,
            int replicasDesired,
            int replicasReady,
            int replicasAvailable,
            int replicasUpdated,
            String creationTimestamp,
            Map<String, String> podSelector,
            List<DeploymentCondition> conditions) {
    }

    record DeploymentCondition(String type, String status, String reason, String message) {
    }

    record PodInfo(
            String uid,
            String name,
            String namespace,
            String resourceVersion,
            String phase,
            boolean ready,
            int restarts,
            String waitingReason,
            String nodeName,
            String creationTimestamp,
            String ownerName) {
    }

    record EventInfo(
            String uid,
            String type,
            String reason,
            String message,
            int count,
            String firstTimestamp,
            String lastTimestamp,
            String involvedKind,
            String involvedName) {
    }
}
