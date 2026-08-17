package com.hhy.dreamingrecall.recording;

public record RecordingMetricsSnapshot(
        long acceptedRecords,
        long droppedEnhancementRecords,
        long droppedCoreRecords,
        long committedSegments,
        long committedUncompressedBytes,
        long committedCompressedBytes,
        int queueDepth,
        long queuedBytes,
        int queueHighWatermark,
        long queueBytesHighWatermark
) {
}
