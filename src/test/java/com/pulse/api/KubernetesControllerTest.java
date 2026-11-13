package com.pulse.api;

import com.pulse.kubernetes.KubernetesException;
import com.pulse.service.ClusterObservationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class KubernetesControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ClusterObservationService observationService;

    private static ClusterObservationService.ClusterSnapshot healthySnapshot() {
        return new ClusterObservationService.ClusterSnapshot(
                "todoapp", "pulse-system", "2026-11-14T06:00:00Z", "READY", 7, "todoapp:v1",
                5, 5,
                new ClusterObservationService.ReplicaStatus(2, 2, 2, 2),
                List.of(), List.of(), true, List.of());
    }

    private static ClusterObservationService.DeploymentSummary summary() {
        return new ClusterObservationService.DeploymentSummary(
                "todoapp", "pulse-system", "uid-1", 7, "todoapp:v1", 5, 5,
                new ClusterObservationService.ReplicaStatus(2, 2, 2, 2),
                "2026-11-14T00:00:00Z");
    }

    // --- 200 paths ----------------------------------------------------------

    @Test
    void listReturns200WithNamespaceAndDeployments() throws Exception {
        when(observationService.deploymentList()).thenReturn(List.of(summary()));

        mockMvc.perform(get("/api/v1/deployments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.namespace").value("pulse-system"))
                .andExpect(jsonPath("$.observedAt").exists())
                .andExpect(jsonPath("$.deployments[0].name").value("todoapp"))
                .andExpect(jsonPath("$.deployments[0].revision").value(7))
                .andExpect(jsonPath("$.deployments[0].replicas.ready").value(2));
    }

    @Test
    void detailReturns200Snapshot() throws Exception {
        when(observationService.snapshot("todoapp")).thenReturn(healthySnapshot());

        mockMvc.perform(get("/api/v1/deployments/todoapp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deployment").value("todoapp"))
                .andExpect(jsonPath("$.observedState").value("READY"))
                .andExpect(jsonPath("$.complete").value(true))
                .andExpect(jsonPath("$.revision").value(7))
                .andExpect(jsonPath("$.observedAt").exists())
                .andExpect(jsonPath("$.notes").isArray());
    }

    @Test
    void partialEvidenceStays200MarkedIncomplete() throws Exception {
        when(observationService.snapshot("todoapp")).thenReturn(new ClusterObservationService.ClusterSnapshot(
                "todoapp", "pulse-system", "2026-11-14T06:00:00Z", "READY", 7, "todoapp:v1",
                5, 5,
                new ClusterObservationService.ReplicaStatus(2, 2, 2, 2),
                List.of(), List.of(), false,
                List.of("events unavailable: FORBIDDEN — Kubernetes HTTP 403 for events")));

        mockMvc.perform(get("/api/v1/deployments/todoapp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.notes[0]").value(org.hamcrest.Matchers.containsString("FORBIDDEN")));
    }

    // --- 404 split ----------------------------------------------------------

    @Test
    void unknownDeploymentIs404UnknownDeployment() throws Exception {
        when(observationService.snapshot("ghost")).thenThrow(KubernetesException.notFound(
                "deployment 'ghost' not found in namespace 'pulse-system'"));

        mockMvc.perform(get("/api/v1/deployments/ghost"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("unknown_deployment"))
                .andExpect(jsonPath("$.deployment").value("ghost"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("ghost")));
    }

    @Test
    void missingNamespaceUpstream404IsNamespaceNotFound() throws Exception {
        when(observationService.snapshot("todoapp")).thenThrow(KubernetesException.notFound(
                "Kubernetes HTTP 404 for deployments: NotFound: namespaces \"nope\" not found"));

        mockMvc.perform(get("/api/v1/deployments/todoapp"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("namespace_not_found"));
    }

    // --- full status map ----------------------------------------------------

    @ParameterizedTest
    @MethodSource("kindStatusCodes")
    void mapsKindToLockedStatusAndCode(KubernetesException.Kind kind, int status, String code) throws Exception {
        when(observationService.snapshot(anyString()))
                .thenThrow(KubernetesException.of(kind, "detail-for-" + kind, null));

        mockMvc.perform(get("/api/v1/deployments/todoapp"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                        .is(status))
                .andExpect(jsonPath("$.error").value(code))
                .andExpect(jsonPath("$.deployment").value("todoapp"))
                .andExpect(jsonPath("$.detail").value("detail-for-" + kind));
    }

    static Stream<Arguments> kindStatusCodes() {
        return Stream.of(
                Arguments.of(KubernetesException.Kind.UNAVAILABLE, 503, "kubernetes_unavailable"),
                Arguments.of(KubernetesException.Kind.UNAUTHENTICATED, 502, "kubernetes_unauthenticated"),
                Arguments.of(KubernetesException.Kind.FORBIDDEN, 403, "kubernetes_forbidden"),
                Arguments.of(KubernetesException.Kind.THROTTLED, 503, "kubernetes_throttled"),
                Arguments.of(KubernetesException.Kind.UPSTREAM_ERROR, 502, "kubernetes_api_failed"),
                Arguments.of(KubernetesException.Kind.BAD_RESPONSE, 502, "kubernetes_api_failed"),
                Arguments.of(KubernetesException.Kind.DEADLINE_EXCEEDED, 504, "kubernetes_deadline_exceeded"),
                Arguments.of(KubernetesException.Kind.DISABLED, 503, "kubernetes_disabled"));
    }

    @Test
    void listFailureMapsStatusWithoutDeploymentField() throws Exception {
        when(observationService.deploymentList()).thenThrow(
                KubernetesException.unavailable("Kubernetes unreachable (Connection refused)", null));

        mockMvc.perform(get("/api/v1/deployments"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("kubernetes_unavailable"))
                .andExpect(jsonPath("$.deployment").doesNotExist());
    }
}
