package com.hhy.dreamingrecall.archive;

public record ArchiveCompletion(
        long completedEpochMillis,
        long durationNanos,
        long segmentCount,
        long acceptedRecords,
        long droppedEnhancementRecords,
        long droppedCoreRecords,
        boolean cleanShutdown
) {
}
