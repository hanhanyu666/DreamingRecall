package com.hhy.dreamingrecall.playback;

import com.hhy.dreamingrecall.archive.ReplayRecord;
import com.hhy.dreamingrecall.archive.SegmentMetadata;
import com.hhy.dreamingrecall.playback.source.ArchiveDataSource;
import com.hhy.dreamingrecall.playback.source.ReadCancellation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

public final class ArchiveTimelineReader {
    private final ArchiveDataSource source;
    private final AtomicReference<ReadCancellation> activeRead = new AtomicReference<>();

    public ArchiveTimelineReader(ArchiveDataSource source) {
        this.source = source;
    }

    public CompletableFuture<List<ReplayRecord>> readRange(long startArchiveNanos, long endArchiveNanos) {
        ReadCancellation cancellation = new ReadCancellation();
        ReadCancellation previous = activeRead.getAndSet(cancellation);
        if (previous != null) {
            previous.cancel();
        }

        List<SegmentMetadata> segments = source.index().segmentsIntersecting(startArchiveNanos, endArchiveNanos);
        List<CompletableFuture<com.hhy.dreamingrecall.archive.SegmentReadResult>> reads = segments.stream()
                .map(segment -> source.readSegment(segment, cancellation))
                .toList();
        return CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
            cancellation.throwIfCancelled();
            ArrayList<ReplayRecord> records = new ArrayList<>();
            for (var read : reads) {
                for (ReplayRecord record : read.join().records()) {
                    if (record.archiveNanos() >= startArchiveNanos && record.archiveNanos() <= endArchiveNanos) {
                        records.add(record);
                    }
                }
            }
            records.sort(Comparator.comparingLong(ReplayRecord::archiveNanos));
            activeRead.compareAndSet(cancellation, null);
            return List.copyOf(records);
        });
    }

    public void cancelActiveRead() {
        ReadCancellation cancellation = activeRead.getAndSet(null);
        if (cancellation != null) {
            cancellation.cancel();
        }
    }
}
