package com.hhy.dreamingrecall.server;

import com.hhy.dreamingrecall.recording.PipelineState;
import com.hhy.dreamingrecall.recording.RecordingMetricsSnapshot;
import com.hhy.dreamingrecall.recording.TickCostSnapshot;

import java.nio.file.Path;

public record ServerRecordingStatus(
        PipelineState pipelineState,
        RecordingMode mode,
        long durationNanos,
        boolean baselineRunning,
        int pendingBaselineChunks,
        int observedChunks,
        RecordingMetricsSnapshot metrics,
        TickCostSnapshot tickCost,
        Path archiveDirectory
) {
}
