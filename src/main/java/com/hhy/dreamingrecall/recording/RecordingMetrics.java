package com.hhy.dreamingrecall.recording;

import com.hhy.dreamingrecall.archive.SegmentMetadata;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

final class RecordingMetrics {
    private final LongAdder acceptedRecords = new LongAdder();
    private final LongAdder droppedEnhancementRecords = new LongAdder();
    private final LongAdder droppedCoreRecords = new LongAdder();
    private final LongAdder committedSegments = new LongAdder();
    private final LongAdder committedUncompressedBytes = new LongAdder();
    private final LongAdder committedCompressedBytes = new LongAdder();
    private final AtomicInteger queueHighWatermark = new AtomicInteger();
    private final AtomicLong queueBytesHighWatermark = new AtomicLong();

    void accepted(int queueDepth, long queuedBytes) {
        acceptedRecords.increment();
        queueHighWatermark.accumulateAndGet(queueDepth, Math::max);
        queueBytesHighWatermark.accumulateAndGet(queuedBytes, Math::max);
    }

    void droppedEnhancement() {
        droppedEnhancementRecords.increment();
    }

    void evictedEnhancement() {
        acceptedRecords.decrement();
        droppedEnhancementRecords.increment();
    }

    void droppedCore() {
        droppedCoreRecords.increment();
    }

    void committed(SegmentMetadata metadata) {
        committedSegments.increment();
        committedUncompressedBytes.add(metadata.uncompressedBytes());
        committedCompressedBytes.add(metadata.compressedBytes());
    }

    RecordingMetricsSnapshot snapshot(int queueDepth, long queuedBytes) {
        return new RecordingMetricsSnapshot(
                acceptedRecords.sum(),
                droppedEnhancementRecords.sum(),
                droppedCoreRecords.sum(),
                committedSegments.sum(),
                committedUncompressedBytes.sum(),
                committedCompressedBytes.sum(),
                queueDepth,
                queuedBytes,
                queueHighWatermark.get(),
                queueBytesHighWatermark.get()
        );
    }
}
