package com.hhy.dreamingrecall.recording;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TickCostWindowTest {
    @Test
    void reportsRecentBoundedPercentiles() {
        TickCostWindow window = new TickCostWindow(100);
        for (int value = 1; value <= 200; value++) {
            window.record(value);
        }

        TickCostSnapshot snapshot = window.snapshot();

        assertEquals(200, snapshot.samples());
        assertEquals(150, snapshot.p50Nanos());
        assertEquals(195, snapshot.p95Nanos());
        assertEquals(199, snapshot.p99Nanos());
        assertEquals(200, snapshot.maximumNanos());
    }
}
