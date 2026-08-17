package com.hhy.dreamingrecall.playback.state;

import com.hhy.dreamingrecall.archive.SegmentMetadata;

import java.util.Objects;

public final class ReplayStateCheckpoint {
    private final long archiveNanos;
    private final long throughSegmentSequence;
    private final ReplayWorldSnapshot snapshot;
    private final SegmentMetadata persistedSegment;

    public ReplayStateCheckpoint(
            long archiveNanos,
            long throughSegmentSequence,
            ReplayWorldSnapshot snapshot
    ) {
        this(archiveNanos, throughSegmentSequence, Objects.requireNonNull(snapshot, "snapshot"), null);
    }

    public static ReplayStateCheckpoint persisted(SegmentMetadata segment) {
        Objects.requireNonNull(segment, "segment");
        return new ReplayStateCheckpoint(segment.endArchiveNanos(), segment.sequence(), null, segment);
    }

    private ReplayStateCheckpoint(
            long archiveNanos,
            long throughSegmentSequence,
            ReplayWorldSnapshot snapshot,
            SegmentMetadata persistedSegment
    ) {
        if (archiveNanos < 0 || throughSegmentSequence < -1) {
            throw new IllegalArgumentException("Invalid replay checkpoint bounds");
        }
        if ((snapshot == null) == (persistedSegment == null)) {
            throw new IllegalArgumentException("A checkpoint must have exactly one state source");
        }
        this.archiveNanos = archiveNanos;
        this.throughSegmentSequence = throughSegmentSequence;
        this.snapshot = snapshot;
        this.persistedSegment = persistedSegment;
    }

    public long archiveNanos() {
        return archiveNanos;
    }

    public long throughSegmentSequence() {
        return throughSegmentSequence;
    }

    public boolean isPersisted() {
        return persistedSegment != null;
    }

    public ReplayWorldSnapshot snapshot() {
        if (snapshot == null) {
            throw new IllegalStateException("Persisted checkpoint must be loaded through its archive data source");
        }
        return snapshot;
    }

    public SegmentMetadata persistedSegment() {
        if (persistedSegment == null) {
            throw new IllegalStateException("In-memory checkpoint has no persisted segment");
        }
        return persistedSegment;
    }
}
