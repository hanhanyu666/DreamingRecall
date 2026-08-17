package com.hhy.dreamingrecall.playback.state;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ReplayStateIndex {
    private final List<ReplayStateCheckpoint> checkpoints;
    private final long durationNanos;
    private final long firstPopulatedNanos;

    public ReplayStateIndex(List<ReplayStateCheckpoint> checkpoints, long durationNanos) {
        this(checkpoints, durationNanos, 0);
    }

    public ReplayStateIndex(
            List<ReplayStateCheckpoint> checkpoints,
            long durationNanos,
            long firstPopulatedNanos
    ) {
        if (checkpoints.isEmpty()) {
            throw new IllegalArgumentException("At least the initial replay checkpoint is required");
        }
        if (durationNanos < 0 || firstPopulatedNanos < 0 || firstPopulatedNanos > durationNanos) {
            throw new IllegalArgumentException("durationNanos must be non-negative");
        }
        ArrayList<ReplayStateCheckpoint> sorted = new ArrayList<>(checkpoints);
        sorted.sort(Comparator.comparingLong(ReplayStateCheckpoint::archiveNanos));
        this.checkpoints = List.copyOf(sorted);
        this.durationNanos = durationNanos;
        this.firstPopulatedNanos = firstPopulatedNanos;
    }

    public List<ReplayStateCheckpoint> checkpoints() {
        return checkpoints;
    }

    public long durationNanos() {
        return durationNanos;
    }

    /** Earliest timestamp at which at least one dimension was reconstructed. */
    public long firstPopulatedNanos() {
        return firstPopulatedNanos;
    }

    public ReplayStateCheckpoint checkpointAtOrBefore(long archiveNanos) {
        int low = 0;
        int high = checkpoints.size() - 1;
        ReplayStateCheckpoint candidate = checkpoints.getFirst();
        while (low <= high) {
            int middle = (low + high) >>> 1;
            ReplayStateCheckpoint current = checkpoints.get(middle);
            if (current.archiveNanos() <= archiveNanos) {
                candidate = current;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return candidate;
    }
}
