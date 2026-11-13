package com.pulse.api;

import com.pulse.config.PulseProperties;
import com.pulse.kubernetes.KubernetesException;
import com.pulse.service.ClusterObservationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP surface of the Kubernetes observer.
 *
 * Locked failure contract:
 *   404 unknown_deployment | namespace_not_found
 *   403 kubernetes_forbidden            (upstream said no — fix RBAC, don't retry)
 *   502 kubernetes_unauthenticated | kubernetes_api_failed (401 / 5xx / malformed)
 *   503 kubernetes_unavailable | throttled | disabled
 *   504 kubernetes_deadline_exceeded
 * Partial evidence (e.g. events denied) stays 200 with complete=false + notes.
 */
@RestController
public class KubernetesController {

    private final PulseProperties properties;
    private final ClusterObservationService observationService;

    public KubernetesController(PulseProperties properties, ClusterObservationService observationService) {
        this.properties = properties;
        this.observationService = observationService;
    }

    @GetMapping("/api/v1/deployments")
    public ResponseEntity<Object> listDeployments() {
        try {
            List<ClusterObservationService.DeploymentSummary> deployments =
                    observationService.deploymentList();
            return ResponseEntity.ok(Map.of(
                    "namespace", properties.getKubernetes().getNamespace(),
                    "observedAt", Instant.now().toString(),
                    "deployments", deployments));
        } catch (KubernetesException e) {
            return error(e, null);
        }
    }

    @GetMapping("/api/v1/deployments/{name}")
    public ResponseEntity<Object> deploymentDetail(@PathVariable String name) {
        try {
            return ResponseEntity.ok(observationService.snapshot(name));
        } catch (KubernetesException e) {
            return error(e, name);
        }
    }

    private static ResponseEntity<Object> error(KubernetesException e, String deployment) {
        KubernetesException.Kind kind = e.getKind();
        String detail = String.valueOf(e.getMessage());

        int status;
        String code;
        switch (kind) {
            case UNAVAILABLE -> { status = 503; code = "kubernetes_unavailable"; }
            case UNAUTHENTICATED -> { status = 502; code = "kubernetes_unauthenticated"; }
            case FORBIDDEN -> { status = 403; code = "kubernetes_forbidden"; }
            case NOT_FOUND -> {
                // Upstream 404 (namespace missing) carries the client's "Kubernetes HTTP 404"
                // prefix; the service's own missing-deployment 404 does not.
                boolean upstream = detail.startsWith("Kubernetes HTTP 404");
                status = 404;
                code = upstream ? "namespace_not_found" : "unknown_deployment";
            }
            case THROTTLED -> { status = 503; code = "kubernetes_throttled"; }
            case DEADLINE_EXCEEDED -> { status = 504; code = "kubernetes_deadline_exceeded"; }
            case DISABLED -> { status = 503; code = "kubernetes_disabled"; }
            case UPSTREAM_ERROR, BAD_RESPONSE -> { status = 502; code = "kubernetes_api_failed"; }
            default -> { status = 500; code = "kubernetes_internal_error"; }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        if (deployment != null) {
            body.put("deployment", deployment);
        }
        body.put("detail", detail);
        return ResponseEntity.status(status).body(body);
    }
}
