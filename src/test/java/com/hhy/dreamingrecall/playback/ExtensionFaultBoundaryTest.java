package com.hhy.dreamingrecall.playback;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExtensionFaultBoundaryTest {
    @Test
    void disablesOnlyFailingExtensionForCurrentBoundaryInstance() {
        AtomicInteger diagnostics = new AtomicInteger();
        ExtensionFaultBoundary boundary = new ExtensionFaultBoundary(3, (id, failure) -> diagnostics.incrementAndGet());

        for (int index = 0; index < 3; index++) {
            assertTrue(boundary.invoke("example:broken", () -> {
                throw new IllegalStateException("broken");
            }).isEmpty());
        }

        assertTrue(boundary.isDisabled("example:broken"));
        assertFalse(boundary.isDisabled("example:healthy"));
        assertEquals("ok", boundary.invoke("example:healthy", () -> "ok").orElseThrow());
        assertEquals(3, diagnostics.get());
    }

    @Test
    void successfulCallsResetTheConsecutiveFailureCount() {
        ExtensionFaultBoundary boundary = new ExtensionFaultBoundary(3, (id, failure) -> {
        });

        boundary.invoke("example:intermittent", () -> {
            throw new IllegalStateException("first");
        });
        boundary.invoke("example:intermittent", () -> "recovered");
        boundary.invoke("example:intermittent", () -> {
            throw new IllegalStateException("second");
        });
        boundary.invoke("example:intermittent", () -> {
            throw new IllegalStateException("third");
        });

        assertFalse(boundary.isDisabled("example:intermittent"));
        assertEquals(2, boundary.failureCount("example:intermittent"));
    }
}
