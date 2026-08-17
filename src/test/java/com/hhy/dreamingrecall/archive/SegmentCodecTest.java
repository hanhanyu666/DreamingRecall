package com.hhy.dreamingrecall.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SegmentCodecTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void committedSegmentRoundTripsWithoutRegistryKnowledge() throws Exception {
        List<ReplayRecord> records = List.of(
                ReplayRecord.control(CoreRecordType.SESSION_START, 0, 0, new byte[]{1, 2, 3}),
                ReplayRecord.core(CoreRecordType.PLAYER_STATE, 25_000_000, 42, "minecraft:overworld", new byte[]{4, 5}),
                new ReplayRecord(54_321, RecordPriority.ENHANCEMENT, 50_000_000, 43, "modded:moon", new byte[]{6, 7, 8})
        );

        SegmentMetadata written = SegmentCodec.commit(temporaryDirectory, 0, records, 1);
        SegmentReadResult read = SegmentCodec.read(written.path());

        assertEquals(records, read.records());
        assertEquals(written, read.metadata());
        assertEquals(written, SegmentCodec.readMetadata(written.path()));
    }

    @Test
    void payloadCorruptionIsDetected() throws Exception {
        SegmentMetadata segment = SegmentCodec.commit(
                temporaryDirectory,
                0,
                List.of(ReplayRecord.control(CoreRecordType.SESSION_START, 0, 0, new byte[]{1, 2, 3, 4})),
                1
        );

        try (RandomAccessFile file = new RandomAccessFile(segment.path().toFile(), "rw")) {
            file.seek(file.length() - 1);
            int original = file.read();
            file.seek(file.length() - 1);
            file.write(original ^ 0x40);
        }

        assertThrows(IOException.class, () -> SegmentCodec.read(segment.path()));
    }

    @Test
    void scannerKeepsValidSegmentsAndReportsPartialTail() throws Exception {
        Path archive = temporaryDirectory.resolve("archive");
        Path segments = Files.createDirectories(archive.resolve("segments"));
        SegmentCodec.commit(
                segments,
                0,
                List.of(ReplayRecord.control(CoreRecordType.SESSION_START, 0, 0, new byte[0])),
                1
        );
        Files.write(segments.resolve(ArchiveFormat.partialFileName(1)), new byte[]{1, 2, 3});

        ArchiveScanResult scan = ArchiveScanner.scan(archive, true);

        assertEquals(1, scan.index().segments().size());
        assertEquals(1, scan.diagnostics().size());
        assertTrue(scan.diagnostics().getFirst().message().contains("uncommitted"));
    }
}
