package com.pulse.kubernetes;

import com.pulse.config.PulseProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class KubernetesAuthTest {

    @TempDir
    Path temp;

    private static PulseProperties.Kubernetes config() {
        return new PulseProperties.Kubernetes();
    }

    private String write(String name, String content) throws Exception {
        Path file = Files.writeString(temp.resolve(name), content);
        return file.toString();
    }

    // --- startup fail-fast (acceptance row: "authentication missing") ------

    @Test
    void unknownAuthModeFailsAtStartup() {
        PulseProperties props = new PulseProperties();
        props.getKubernetes().setEnabled(true);
        props.getKubernetes().setAuthMode("ambient-magic");

        KubernetesConfigurationException ex = assertThrows(KubernetesConfigurationException.class,
                () -> new HttpKubernetesClient(props));

        assertTrue(ex.getMessage().contains("auth-mode"), ex.getMessage());
        assertTrue(ex.getMessage().contains("in-cluster"), ex.getMessage());
        assertTrue(ex.getMessage().contains("kubeconfig"), ex.getMessage());
        assertTrue(ex.getMessage().contains("never fall back"), ex.getMessage());
    }

    @Test
    void inClusterMissingTokenFileFailsAtStartup() {
        PulseProperties props = new PulseProperties();
        props.getKubernetes().setEnabled(true);
        props.getKubernetes().setTokenFile(temp.resolve("no-such-token").toString());

        KubernetesConfigurationException ex = assertThrows(KubernetesConfigurationException.class,
                () -> new HttpKubernetesClient(props));

        assertTrue(ex.getMessage().contains("token-file"), ex.getMessage());
        assertTrue(ex.getMessage().contains("no-such-token"), ex.getMessage());
    }

    @Test
    void kubeconfigMissingFileFailsAtStartup() {
        PulseProperties props = new PulseProperties();
        props.getKubernetes().setEnabled(true);
        props.getKubernetes().setAuthMode("kubeconfig");
        props.getKubernetes().setKubeconfigPath(temp.resolve("missing-kubeconfig").toString());

        KubernetesConfigurationException ex = assertThrows(KubernetesConfigurationException.class,
                () -> new HttpKubernetesClient(props));

        assertTrue(ex.getMessage().contains("file not found"), ex.getMessage());
        assertTrue(ex.getMessage().contains("missing-kubeconfig"), ex.getMessage());
    }

    // --- in-cluster mode ----------------------------------------------------

    @Test
    void inClusterReadsTokenAndDefaultsToPlatformServer() throws Exception {
        String tokenPath = write("token", "sa-token-123");
        PulseProperties.Kubernetes cfg = config();
        cfg.setAuthMode("in-cluster");
        cfg.setTokenFile(tokenPath);
        cfg.setCaCertFile(""); // no CA configured → JVM default trust

        KubernetesAuth.Resolved resolved = KubernetesAuth.resolve(cfg);

        assertEquals("https://kubernetes.default.svc", resolved.serverUrl());
        assertEquals("sa-token-123", resolved.tokenSupplier().get());
        assertNull(resolved.sslContext());
    }

    @Test
    void inClusterEmptyTokenFileFailsFast() throws Exception {
        String tokenPath = write("empty-token", "   ");
        PulseProperties.Kubernetes cfg = config();
        cfg.setTokenFile(tokenPath);

        KubernetesConfigurationException ex = assertThrows(KubernetesConfigurationException.class,
                () -> KubernetesAuth.resolve(cfg));

        assertTrue(ex.getMessage().contains("empty"), ex.getMessage());
    }

    @Test
    void inClusterGarbageCaFileFailsFast() throws Exception {
        String tokenPath = write("token", "sa-token");
        String caPath = write("ca.crt", "this is not a certificate");
        PulseProperties.Kubernetes cfg = config();
        cfg.setTokenFile(tokenPath);
        cfg.setCaCertFile(caPath);

        KubernetesConfigurationException ex = assertThrows(KubernetesConfigurationException.class,
                () -> KubernetesAuth.resolve(cfg));

        assertTrue(ex.getMessage().contains("invalid CA"), ex.getMessage());
        assertTrue(ex.getMessage().contains(caPath), ex.getMessage());
    }

    // --- kubeconfig mode ----------------------------------------------------

    @Test
    void kubeconfigResolvesServerAndInlineToken() throws Exception {
        String path = write("config", """
                apiVersion: v1
                kind: Config
                current-context: ctx1
                clusters:
                - name: cl1
                  cluster:
                    server: https://10.0.0.1:6443
                users:
                - name: u1
                  user:
                    token: token-abc
                contexts:
                - name: ctx1
                  context:
                    cluster: cl1
                    user: u1
                """);
        PulseProperties.Kubernetes cfg = config();
        cfg.setAuthMode("kubeconfig");
        cfg.setKubeconfigPath(path);

        KubernetesAuth.Resolved resolved = KubernetesAuth.resolve(cfg);

        assertEquals("https://10.0.0.1:6443", resolved.serverUrl());
        assertEquals("token-abc", resolved.tokenSupplier().get());
        assertNull(resolved.sslContext());
    }

    @Test
    void kubeconfigExecAuthIsRejectedExplicitly() throws Exception {
        String path = write("config-exec", """
                apiVersion: v1
                kind: Config
                current-context: ctx1
                clusters:
                - name: cl1
                  cluster:
                    server: https://eks.example:6443
                users:
                - name: u1
                  user:
                    exec:
                      command: aws
                      args: [eks, get-token]
                contexts:
                - name: ctx1
                  context:
                    cluster: cl1
                    user: u1
                """);
        PulseProperties.Kubernetes cfg = config();
        cfg.setAuthMode("kubeconfig");
        cfg.setKubeconfigPath(path);

        KubernetesConfigurationException ex = assertThrows(KubernetesConfigurationException.class,
                () -> KubernetesAuth.resolve(cfg));

        assertTrue(ex.getMessage().contains("exec"), ex.getMessage());
        assertTrue(ex.getMessage().contains("in-cluster"), ex.getMessage());
    }

    @Test
    void kubeconfigInsecureSkipTlsIsRejected() throws Exception {
        String path = write("config-insecure", """
                apiVersion: v1
                kind: Config
                current-context: ctx1
                clusters:
                - name: cl1
                  cluster:
                    server: https://10.0.0.1:6443
                    insecure-skip-tls-verify: true
                users:
                - name: u1
                  user:
                    token: token-abc
                contexts:
                - name: ctx1
                  context:
                    cluster: cl1
                    user: u1
                """);
        PulseProperties.Kubernetes cfg = config();
        cfg.setAuthMode("kubeconfig");
        cfg.setKubeconfigPath(path);

        KubernetesConfigurationException ex = assertThrows(KubernetesConfigurationException.class,
                () -> KubernetesAuth.resolve(cfg));

        assertTrue(ex.getMessage().contains("insecure-skip-tls-verify"), ex.getMessage());
        assertTrue(ex.getMessage().contains("certificate-authority"), ex.getMessage());
    }

    @Test
    void kubeconfigUserWithoutTokenIsRejected() throws Exception {
        String path = write("config-notoken", """
                apiVersion: v1
                kind: Config
                current-context: ctx1
                clusters:
                - name: cl1
                  cluster:
                    server: https://10.0.0.1:6443
                users:
                - name: u1
                  user: {}
                contexts:
                - name: ctx1
                  context:
                    cluster: cl1
                    user: u1
                """);
        PulseProperties.Kubernetes cfg = config();
        cfg.setAuthMode("kubeconfig");
        cfg.setKubeconfigPath(path);

        KubernetesConfigurationException ex = assertThrows(KubernetesConfigurationException.class,
                () -> KubernetesAuth.resolve(cfg));

        assertTrue(ex.getMessage().contains("no token"), ex.getMessage());
    }
}
