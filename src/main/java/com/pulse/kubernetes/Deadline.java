package com.pulse.kubernetes;

/**
 * Hard time budget for one observation (all paginated calls combined).
 * Created once per observation by the service and passed into every client call.
 */
public final class Deadline {

    private final long deadlineNanos;

    Deadline(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    public static Deadline afterMillis(long millis) {
        if (millis <= 0) {
            throw new IllegalArgumentException("deadline must be positive, got " + millis);
        }
        return new Deadline(System.nanoTime() + millis * 1_000_000L);
    }

    public long remainingMillis() {
        long remaining = (deadlineNanos - System.nanoTime()) / 1_000_000L;
        return Math.max(remaining, 0L);
    }

    public boolean isExpired() {
        return System.nanoTime() >= deadlineNanos;
    }
}
