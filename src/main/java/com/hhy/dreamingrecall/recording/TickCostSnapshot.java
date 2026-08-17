package com.hhy.dreamingrecall.recording;

public record TickCostSnapshot(long samples, long p50Nanos, long p95Nanos, long p99Nanos, long maximumNanos) {
}
