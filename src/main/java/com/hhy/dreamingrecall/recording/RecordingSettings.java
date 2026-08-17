package com.hhy.dreamingrecall.recording;

import java.time.Duration;

public record RecordingSettings(
        int queueCapacity,
        long maxQueuedBytes,
        int maxRecordBytes,
        Duration segmentDuration,
        int segmentTargetBytes,
        int compressionLevel,
        Duration checkpointInterval,
        Duration writerPollInterval
) {
    public RecordingSettings {
        if (queueCapacity < 16) {
            throw new IllegalArgumentException("queueCapacity must be at least 16");
        }
        if (maxQueuedBytes < 1024 || maxRecordBytes < 1 || maxRecordBytes > maxQueuedBytes) {
            throw new IllegalArgumentException("Invalid recording byte limits");
        }
        if (segmentDuration.isNegative() || segmentDuration.isZero()) {
            throw new IllegalArgumentException("segmentDuration must be positive");
        }
        if (segmentTargetBytes < 1024 || segmentTargetBytes > maxQueuedBytes) {
            throw new IllegalArgumentException("Invalid segment target size");
        }
        if (compressionLevel < 0 || compressionLevel > 9) {
            throw new IllegalArgumentException("compressionLevel must be between 0 and 9");
        }
        if (checkpointInterval.isNegative() || checkpointInterval.isZero()) {
            throw new IllegalArgumentException("checkpointInterval must be positive");
        }
        if (writerPollInterval.isNegative() || writerPollInterval.isZero()) {
            throw new IllegalArgumentException("writerPollInterval must be positive");
        }
    }

    public static RecordingSettings defaults() {
        return new RecordingSettings(
                8192,
                64L * 1024 * 1024,
                16 * 1024 * 1024,
                Duration.ofSeconds(10),
                16 * 1024 * 1024,
                1,
                Duration.ofSeconds(30),
                Duration.ofMillis(50)
        );
    }
}
