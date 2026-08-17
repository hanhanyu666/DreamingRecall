package com.hhy.dreamingrecall.playback;

import com.hhy.dreamingrecall.archive.ArchiveLayout;
import com.hhy.dreamingrecall.archive.ArchiveManifest;
import com.hhy.dreamingrecall.archive.CoreRecordType;
import com.hhy.dreamingrecall.archive.ReplayRecord;
import com.hhy.dreamingrecall.archive.SegmentCodec;
import com.hhy.dreamingrecall.playback.source.LocalArchiveDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArchiveTimelineReaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsOnlyRecordsInsideRequestedTimeWindow() throws Exception {
        Path archive = ArchiveLayout.initialize(
                temporaryDirectory,
                ArchiveManifest.create("1.21.1", "test", ArchiveManifest.SourceKind.SINGLEPLAYER)
        );
        SegmentCodec.commit(
                archive.resolve("segments"),
                0,
                List.of(
                        ReplayRecord.control(CoreRecordType.SESSION_START, 0, 0, new byte[0]),
                        ReplayRecord.core(CoreRecordType.SERVER_TICK, 10, 1, "", new byte[0])
                ),
                1
        );
        SegmentCodec.commit(
                archive.resolve("segments"),
                1,
                List.of(
                        ReplayRecord.core(CoreRecordType.SERVER_TICK, 20, 2, "", new byte[0]),
                        ReplayRecord.control(CoreRecordType.SESSION_END, 30, 3, new byte[0])
                ),
                1
        );

        try (LocalArchiveDataSource source = LocalArchiveDataSource.open(archive, "1.21.1")) {
            List<ReplayRecord> records = new ArchiveTimelineReader(source)
                    .readRange(9, 21)
                    .get(5, TimeUnit.SECONDS);
            assertEquals(List.of(10L, 20L), records.stream().map(ReplayRecord::archiveNanos).toList());
        }
    }
}
