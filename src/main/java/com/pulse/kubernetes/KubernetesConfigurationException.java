package com.pulse.kubernetes;

/**
 * Fail-fast startup error: bad auth-mode, missing credential files, unparsable
 * kubeconfig. Thrown while the bean is constructed so Pulse refuses to start
 * with a half-configured observer instead of failing on the first request.
 */
public class KubernetesConfigurationException extends RuntimeException {

    public KubernetesConfigurationException(String message) {
        super(message);
    }

    public KubernetesConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
