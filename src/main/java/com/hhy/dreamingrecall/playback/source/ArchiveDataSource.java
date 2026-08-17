package com.hhy.dreamingrecall.playback.source;

import com.hhy.dreamingrecall.archive.ArchiveIndex;
import com.hhy.dreamingrecall.archive.ArchiveManifest;
import com.hhy.dreamingrecall.archive.SegmentMetadata;
import com.hhy.dreamingrecall.archive.SegmentReadResult;

import java.util.concurrent.CompletableFuture;

public interface ArchiveDataSource extends AutoCloseable {
    ArchiveManifest manifest();

    ArchiveIndex index();

    default ArchiveIndex checkpointIndex() {
        return new ArchiveIndex(java.util.List.of());
    }

    CompletableFuture<SegmentReadResult> readSegment(SegmentMetadata segment, ReadCancellation cancellation);

    default CompletableFuture<SegmentReadResult> readRawSegment(
            SegmentMetadata segment,
            ReadCancellation cancellation
    ) {
        return readSegment(segment, cancellation);
    }

    @Override
    void close();
}
