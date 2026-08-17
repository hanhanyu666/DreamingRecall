package com.hhy.dreamingrecall.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveInspectorTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void countsKnownAndUnknownRecordTypes() throws Exception {
        ArchiveManifest manifest = ArchiveManifest.create("1.21.1", "test", ArchiveManifest.SourceKind.SINGLEPLAYER);
        Path archive = ArchiveLayout.initialize(temporaryDirectory, manifest);
        SegmentCodec.commit(
                archive.resolve("segments"),
                0,
                List.of(
                        ReplayRecord.control(CoreRecordType.SESSION_START, 0, 0, new byte[0]),
                        new ReplayRecord(50_000, RecordPriority.ENHANCEMENT, 5, 1, "", new byte[0])
                ),
                1
        );

        ArchiveInspection inspection = ArchiveInspector.inspect(archive);

        assertTrue(inspection.isHealthy());
        assertEquals(2, inspection.validRecords());
        assertEquals(1, ArchiveInspector.namedRecordCounts(inspection).get("SESSION_START"));
        assertEquals(1, ArchiveInspector.namedRecordCounts(inspection).get("UNKNOWN_50000"));
    }
}
