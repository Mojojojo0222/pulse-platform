package com.pulse.kubernetes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeadlineTest {

    @Test
    void rejectsNonPositiveBudget() {
        assertThrows(IllegalArgumentException.class, () -> Deadline.afterMillis(0));
        assertThrows(IllegalArgumentException.class, () -> Deadline.afterMillis(-5));
    }

    @Test
    void freshDeadlineHasPositiveRemainingWithinBudget() {
        Deadline deadline = Deadline.afterMillis(10_000);
        assertFalse(deadline.isExpired());
        assertTrue(deadline.remainingMillis() > 0);
        assertTrue(deadline.remainingMillis() <= 10_000);
    }

    @Test
    void pastDeadlineIsExpiredWithZeroRemaining() {
        Deadline deadline = new Deadline(System.nanoTime() - 1_000_000L);
        assertTrue(deadline.isExpired());
        assertEquals(0L, deadline.remainingMillis());
    }
}
