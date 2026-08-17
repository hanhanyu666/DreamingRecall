package com.hhy.dreamingrecall.archive;

import java.nio.file.Path;

public record SegmentMetadata(
        Path path,
        long sequence,
        long startArchiveNanos,
        long endArchiveNanos,
        int recordCount,
        int uncompressedBytes,
        int compressedBytes,
        int payloadCrc32c
) {
}
