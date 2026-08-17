package com.hhy.dreamingrecall.archive;

import com.hhy.dreamingrecall.archive.track.TrackCatalogCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveV2CompatibilityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void v2ArchiveUsesAV2SegmentContainerAndTrackCatalog() throws Exception {
        ArchiveManifest manifest = ArchiveManifest.create(
                "1.21.1",
                "test",
                ArchiveManifest.SourceKind.SINGLEPLAYER
        );
        Path archive = ArchiveLayout.initialize(temporaryDirectory, manifest);
        assertEquals(2, manifest.formatMajor());
        assertTrue(Files.isRegularFile(archive.resolve(TrackCatalogCodec.FILE_NAME)));

        SegmentMetadata segment = SegmentCodec.commit(
                archive.resolve("segments"),
                0,
                List.of(ReplayRecord.control(CoreRecordType.SESSION_START, 0, 0, new byte[0])),
                1
        );
        try (DataInputStream input = new DataInputStream(Files.newInputStream(segment.path()))) {
            assertEquals(ArchiveFormat.SEGMENT_MAGIC, input.readInt());
            assertEquals(ArchiveFormat.SEGMENT_FORMAT_MAJOR, input.readUnsignedShort());
            assertEquals(ArchiveFormat.SEGMENT_FORMAT_MINOR, input.readUnsignedShort());
        }
        assertEquals(1, SegmentCodec.read(segment.path()).records().size());
    }
}
