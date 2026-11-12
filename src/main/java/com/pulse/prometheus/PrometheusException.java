package com.pulse.prometheus;

public class PrometheusException extends RuntimeException {

    public enum Kind { UNAVAILABLE, QUERY_FAILED }

    private final Kind kind;

    private PrometheusException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind getKind() {
        return kind;
    }

    public static PrometheusException unavailable(String message, Throwable cause) {
        return new PrometheusException(Kind.UNAVAILABLE, message, cause);
    }

    public static PrometheusException queryFailed(String message, Throwable cause) {
        return new PrometheusException(Kind.QUERY_FAILED, message, cause);
    }

    public static PrometheusException queryFailed(String message) {
        return new PrometheusException(Kind.QUERY_FAILED, message, null);
    }
}
