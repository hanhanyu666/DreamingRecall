package com.hhy.dreamingrecall.client.library;

import com.hhy.dreamingrecall.archive.ArchiveCompletion;
import com.hhy.dreamingrecall.archive.ArchiveLayout;
import com.hhy.dreamingrecall.archive.ArchiveManifest;
import com.hhy.dreamingrecall.archive.ArchiveManifestCodec;
import com.hhy.dreamingrecall.archive.CoreRecordType;
import com.hhy.dreamingrecall.archive.ReplayRecord;
import com.hhy.dreamingrecall.archive.SegmentCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientArchiveLibraryTest {
    @TempDir
    Path gameDirectory;

    @Test
    void discoversImportedAndSingleplayerArchives() throws Exception {
        Path imported = createArchive(
                ClientArchiveLibrary.importedArchiveRoot(gameDirectory),
                ArchiveManifest.SourceKind.DEDICATED_SERVER,
                20_000,
                true
        );
        Path singleplayer = createArchive(
                gameDirectory.resolve("saves").resolve("My World").resolve("dreamingrecall").resolve("replays"),
                ArchiveManifest.SourceKind.SINGLEPLAYER,
                10_000,
                false
        );
        Files.createDirectories(gameDirectory.resolve("dreamingrecall").resolve("replays").resolve("broken"));
        Files.writeString(
                gameDirectory.resolve("dreamingrecall").resolve("replays").resolve("broken").resolve("manifest.json"),
                "not json"
        );

        ClientArchiveScan scan = ClientArchiveLibrary.scan(gameDirectory);

        assertEquals(2, scan.archives().size());
        assertEquals(imported, scan.archives().getFirst().directory());
        assertEquals("Imported", scan.archives().getFirst().sourceLabel());
        assertTrue(scan.archives().getFirst().complete());
        assertEquals(singleplayer, scan.archives().getLast().directory());
        assertEquals("My World", scan.archives().getLast().sourceLabel());
        assertEquals(1, scan.errors().size());
    }

    private static Path createArchive(
            Path root,
            ArchiveManifest.SourceKind sourceKind,
            long createdOffset,
            boolean complete
    ) throws Exception {
        ArchiveManifest generated = ArchiveManifest.create("1.21.1", "test", sourceKind);
        ArchiveManifest manifest = new ArchiveManifest(
                generated.archiveId(),
                generated.formatMajor(),
                generated.formatMinor(),
                generated.minecraftVersion(),
                generated.recorderVersion(),
                generated.sourceKind(),
                generated.createdEpochMillis() + createdOffset
        );
        Path archive = ArchiveLayout.initialize(root, manifest);
        SegmentCodec.commit(
                archive.resolve("segments"),
                0,
                List.of(
                        ReplayRecord.control(CoreRecordType.SESSION_START, 0, 0, new byte[0]),
                        ReplayRecord.control(CoreRecordType.SESSION_END, 50, 1, new byte[0])
                ),
                1
        );
        if (complete) {
            ArchiveManifestCodec.writeCompletion(archive, new ArchiveCompletion(1, 50, 1, 0, 0, 0, true));
        }
        return archive.toAbsolutePath().normalize();
    }
}
