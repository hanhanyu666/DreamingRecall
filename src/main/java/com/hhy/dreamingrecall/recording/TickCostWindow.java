package com.hhy.dreamingrecall.recording;

import java.util.Arrays;

public final class TickCostWindow {
    private final long[] samples;
    private int nextIndex;
    private int size;
    private long totalSamples;

    public TickCostWindow(int capacity) {
        if (capacity < 100) {
            throw new IllegalArgumentException("Tick cost window must hold at least 100 samples");
        }
        this.samples = new long[capacity];
    }

    public void record(long nanos) {
        samples[nextIndex] = Math.max(0, nanos);
        nextIndex = (nextIndex + 1) % samples.length;
        size = Math.min(size + 1, samples.length);
        totalSamples++;
    }

    public TickCostSnapshot snapshot() {
        if (size == 0) {
            return new TickCostSnapshot(0, 0, 0, 0, 0);
        }
        long[] sorted = Arrays.copyOf(samples, size);
        Arrays.sort(sorted);
        return new TickCostSnapshot(
                totalSamples,
                percentile(sorted, 0.50),
                percentile(sorted, 0.95),
                percentile(sorted, 0.99),
                sorted[sorted.length - 1]
        );
    }

    private static long percentile(long[] sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }
}
