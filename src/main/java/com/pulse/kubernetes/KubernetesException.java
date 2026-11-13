package com.pulse.kubernetes;

/**
 * Request-time failure taxonomy. Mapped to HTTP statuses by the controller (M3):
 * UNAVAILABLE→503, UNAUTHENTICATED→502, FORBIDDEN→403, NOT_FOUND→404,
 * THROTTLED→503, UPSTREAM_ERROR→502, BAD_RESPONSE→502,
 * DEADLINE_EXCEEDED→504, DISABLED→503.
 * Messages are already sanitized/truncated by the client; never attach raw upstream bodies.
 */
public class KubernetesException extends RuntimeException {

    public enum Kind {
        UNAVAILABLE,
        UNAUTHENTICATED,
        FORBIDDEN,
        NOT_FOUND,
        THROTTLED,
        UPSTREAM_ERROR,
        BAD_RESPONSE,
        DEADLINE_EXCEEDED,
        DISABLED
    }

    private final Kind kind;

    private KubernetesException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    public static KubernetesException of(Kind kind, String message, Throwable cause) {
        return new KubernetesException(kind, message, cause);
    }

    public static KubernetesException unavailable(String message, Throwable cause) {
        return new KubernetesException(Kind.UNAVAILABLE, message, cause);
    }

    public static KubernetesException unauthenticated(String message, Throwable cause) {
        return new KubernetesException(Kind.UNAUTHENTICATED, message, cause);
    }

    public static KubernetesException unauthenticated(String message) {
        return new KubernetesException(Kind.UNAUTHENTICATED, message, null);
    }

    public static KubernetesException forbidden(String message) {
        return new KubernetesException(Kind.FORBIDDEN, message, null);
    }

    public static KubernetesException notFound(String message) {
        return new KubernetesException(Kind.NOT_FOUND, message, null);
    }

    public static KubernetesException throttled(String message) {
        return new KubernetesException(Kind.THROTTLED, message, null);
    }

    public static KubernetesException upstreamError(String message) {
        return new KubernetesException(Kind.UPSTREAM_ERROR, message, null);
    }

    public static KubernetesException badResponse(String message) {
        return new KubernetesException(Kind.BAD_RESPONSE, message, null);
    }

    public static KubernetesException badResponse(String message, Throwable cause) {
        return new KubernetesException(Kind.BAD_RESPONSE, message, cause);
    }

    public static KubernetesException deadlineExceeded(String message, Throwable cause) {
        return new KubernetesException(Kind.DEADLINE_EXCEEDED, message, cause);
    }

    public static KubernetesException disabled(String message) {
        return new KubernetesException(Kind.DISABLED, message, null);
    }
}
