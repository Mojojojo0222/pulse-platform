package com.pulse.kubernetes;

import com.pulse.config.PulseProperties;
import org.yaml.snakeyaml.Yaml;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Resolves credentials for exactly ONE explicitly configured auth mode.
 * There is no silent fallback between modes: a missing or unusable credential
 * source is a {@link KubernetesConfigurationException} at startup, never a
 * surprise at request time and never a switch to another source.
 */
final class KubernetesAuth {

    static final String MODE_IN_CLUSTER = "in-cluster";
    static final String MODE_KUBECONFIG = "kubeconfig";

    /**
     * serverUrl: where to talk; tokenSupplier: re-read on every request (rotation-safe);
     * sslContext: null = JVM default trust store.
     */
    record Resolved(String serverUrl, Supplier<String> tokenSupplier, SSLContext sslContext) {
    }

    private KubernetesAuth() {
    }

    static Resolved resolve(PulseProperties.Kubernetes cfg) {
        String mode = cfg.getAuthMode() == null ? "" : cfg.getAuthMode().trim().toLowerCase(Locale.ROOT);
        return switch (mode) {
            case MODE_IN_CLUSTER -> inCluster(cfg);
            case MODE_KUBECONFIG -> kubeconfig(cfg);
            default -> throw new KubernetesConfigurationException(
                    "unknown pulse.kubernetes.auth-mode '" + cfg.getAuthMode()
                            + "' — supported values: " + MODE_IN_CLUSTER + ", " + MODE_KUBECONFIG
                            + " (credentials never fall back between modes)");
        };
    }

    static Resolved inCluster(PulseProperties.Kubernetes cfg) {
        String tokenPath = cfg.getTokenFile();
        if (tokenPath == null || tokenPath.isBlank()) {
            throw new KubernetesConfigurationException(
                    "auth-mode in-cluster requires pulse.kubernetes.token-file (ServiceAccount token path)");
        }
        readRequiredFile(tokenPath, "auth-mode in-cluster: ServiceAccount token (pulse.kubernetes.token-file)");

        SSLContext ssl = null;
        String caPath = cfg.getCaCertFile();
        if (caPath != null && !caPath.isBlank()) {
            byte[] pem = readRequiredFile(caPath, "auth-mode in-cluster: CA certificate (pulse.kubernetes.ca-cert-file)");
            ssl = sslFromPem(pem, caPath);
        }

        Supplier<String> token = () -> {
            byte[] bytes = readRequiredFile(tokenPath,
                    "auth-mode in-cluster: ServiceAccount token (pulse.kubernetes.token-file)");
            return new String(bytes, StandardCharsets.UTF_8).trim();
        };
        return new Resolved("https://kubernetes.default.svc", token, ssl);
    }

    static Resolved kubeconfig(PulseProperties.Kubernetes cfg) {
        Path path = kubeconfigPath(cfg);
        if (!Files.isRegularFile(path)) {
            throw new KubernetesConfigurationException(
                    "auth-mode kubeconfig: file not found: " + path
                            + " (set PULSE_KUBECONFIG or pulse.kubernetes.kubeconfig-path)");
        }

        Map<String, Object> root = loadYaml(path);
        String contextName = string(root.get("current-context"));
        Map<String, Object> context = pick(root.get("contexts"), contextName, "contexts", path);
        Map<String, Object> contextBody = map(context.get("context"), path, "context");

        String clusterName = requiredString(contextBody.get("cluster"), path, "context.cluster");
        String userName = requiredString(contextBody.get("user"), path, "context.user");

        Map<String, Object> clusterEntry = pick(root.get("clusters"), clusterName, "clusters", path);
        Map<String, Object> cluster = map(clusterEntry.get("cluster"), path, "cluster");
        String server = requiredString(cluster.get("server"), path, "cluster.server");
        SSLContext ssl = clusterSsl(cluster, path);

        Map<String, Object> userEntry = pick(root.get("users"), userName, "users", path);
        Map<String, Object> user = map(userEntry.get("user"), path, "user");
        Supplier<String> token = userToken(user, path);

        return new Resolved(server, token, ssl);
    }

    // --- kubeconfig details -------------------------------------------------

    private static Path kubeconfigPath(PulseProperties.Kubernetes cfg) {
        if (cfg.getKubeconfigPath() != null && !cfg.getKubeconfigPath().isBlank()) {
            return Path.of(cfg.getKubeconfigPath().trim());
        }
        String env = System.getenv("KUBECONFIG");
        if (env != null && !env.isBlank()) {
            return Path.of(env.trim());
        }
        return Path.of(System.getProperty("user.home", "."), ".kube", "config");
    }

    private static Map<String, Object> loadYaml(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map<?, ?>)) {
                throw new KubernetesConfigurationException(
                        "auth-mode kubeconfig: " + path + " is not a kubeconfig YAML mapping");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> root = (Map<String, Object>) loaded;
            return root;
        } catch (IOException e) {
            throw new KubernetesConfigurationException(
                    "auth-mode kubeconfig: cannot read " + path + " (" + e.getMessage() + ")", e);
        } catch (KubernetesConfigurationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new KubernetesConfigurationException(
                    "auth-mode kubeconfig: invalid YAML in " + path + " (" + e.getMessage() + ")", e);
        }
    }

    /** Picks the entry by name (or the first entry when name is blank). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> pick(Object listNode, String name, String section, Path path) {
        if (!(listNode instanceof List<?> list) || list.isEmpty()) {
            throw new KubernetesConfigurationException(
                    "auth-mode kubeconfig: " + path + " has no '" + section + "' entries");
        }
        for (Object entry : list) {
            if (!(entry instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> candidate = (Map<String, Object>) entry;
            if (name == null || name.isBlank() || name.equals(string(candidate.get("name")))) {
                return candidate;
            }
        }
        throw new KubernetesConfigurationException(
                "auth-mode kubeconfig: " + section + " entry '" + name + "' not found in " + path);
    }

    private static Map<String, Object> map(Object node, Path path, String what) {
        if (node instanceof Map<?, ?>) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cast = (Map<String, Object>) node;
            return cast;
        }
        throw new KubernetesConfigurationException(
                "auth-mode kubeconfig: missing '" + what + "' in " + path);
    }

    private static String requiredString(Object node, Path path, String what) {
        String value = string(node);
        if (value == null || value.isBlank()) {
            throw new KubernetesConfigurationException(
                    "auth-mode kubeconfig: missing '" + what + "' in " + path);
        }
        return value;
    }

    private static SSLContext clusterSsl(Map<String, Object> cluster, Path path) {
        if (Boolean.TRUE.equals(cluster.get("insecure-skip-tls-verify"))) {
            throw new KubernetesConfigurationException(
                    "auth-mode kubeconfig: insecure-skip-tls-verify is not supported in " + path
                            + " — provide cluster.certificate-authority instead");
        }
        String caData = string(cluster.get("certificate-authority-data"));
        if (caData != null && !caData.isBlank()) {
            byte[] pem;
            try {
                pem = Base64.getDecoder().decode(caData.trim());
            } catch (IllegalArgumentException e) {
                throw new KubernetesConfigurationException(
                        "auth-mode kubeconfig: certificate-authority-data is not valid base64 in " + path, e);
            }
            return sslFromPem(pem, path + " (certificate-authority-data)");
        }
        String caFile = string(cluster.get("certificate-authority"));
        if (caFile != null && !caFile.isBlank()) {
            Path caPath = Path.of(caFile);
            if (!caPath.isAbsolute()) {
                caPath = path.getParent() != null ? path.getParent().resolve(caFile) : caPath;
            }
            byte[] pem = readRequiredFile(caPath.toString(),
                    "auth-mode kubeconfig: cluster.certificate-authority");
            return sslFromPem(pem, caPath.toString());
        }
        return null; // no CA configured → JVM default trust store
    }

    private static Supplier<String> userToken(Map<String, Object> user, Path path) {
        if (user.containsKey("exec")) {
            throw new KubernetesConfigurationException(
                    "auth-mode kubeconfig: exec-based authentication (e.g. aws eks get-token) is not supported in "
                            + path + " — export a static token into user.token or use auth-mode in-cluster");
        }
        if (user.containsKey("client-certificate") || user.containsKey("client-certificate-data")) {
            throw new KubernetesConfigurationException(
                    "auth-mode kubeconfig: client-certificate authentication is not supported in " + path
                            + " — use user.token (or user.tokenFile) instead");
        }
        String token = string(user.get("token"));
        if (token != null && !token.isBlank()) {
            String value = token.trim();
            return () -> value;
        }
        String tokenFile = string(user.get("tokenFile"));
        if (tokenFile != null && !tokenFile.isBlank()) {
            Path resolved = Path.of(tokenFile);
            if (!resolved.isAbsolute() && path.getParent() != null) {
                resolved = path.getParent().resolve(tokenFile);
            }
            String absolute = resolved.toString();
            return () -> new String(readRequiredFile(absolute,
                    "auth-mode kubeconfig: user.tokenFile"), StandardCharsets.UTF_8).trim();
        }
        throw new KubernetesConfigurationException(
                "auth-mode kubeconfig: user in " + path + " has no token (user.token / user.tokenFile)");
    }

    // --- shared helpers -----------------------------------------------------

    private static byte[] readRequiredFile(String filePath, String what) {
        if (filePath == null || filePath.isBlank()) {
            throw new KubernetesConfigurationException(what + " path is not configured");
        }
        Path path = Path.of(filePath);
        if (!Files.isRegularFile(path)) {
            throw new KubernetesConfigurationException(what + " not found: " + filePath);
        }
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (new String(bytes, StandardCharsets.UTF_8).trim().isEmpty()) {
                throw new KubernetesConfigurationException(what + " is empty: " + filePath);
            }
            return bytes;
        } catch (IOException e) {
            throw new KubernetesConfigurationException(what + " unreadable: " + filePath + " (" + e.getMessage() + ")", e);
        }
    }

    static SSLContext sslFromPem(byte[] pem, String source) {
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            var certificates = factory.generateCertificates(new ByteArrayInputStream(pem));
            if (certificates.isEmpty()) {
                throw new KubernetesConfigurationException("CA source contains no certificates: " + source);
            }
            KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
            keyStore.load(null, null);
            int i = 0;
            for (Certificate certificate : certificates) {
                keyStore.setCertificateEntry("ca-" + (i++), certificate);
            }
            TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            trustManagers.init(keyStore);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, trustManagers.getTrustManagers(), null);
            return sslContext;
        } catch (KubernetesConfigurationException e) {
            throw e;
        } catch (CertificateException | java.security.KeyStoreException | java.security.NoSuchAlgorithmException
                 | java.security.KeyManagementException | IOException e) {
            throw new KubernetesConfigurationException(
                    "invalid CA certificate in " + source + " (" + e.getMessage() + ")", e);
        }
    }

    private static String string(Object node) {
        return node instanceof String s ? s : null;
    }
}
