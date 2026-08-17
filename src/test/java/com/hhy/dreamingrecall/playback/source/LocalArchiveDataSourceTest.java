package com.hhy.dreamingrecall.playback.source;

import com.hhy.dreamingrecall.archive.ArchiveLayout;
import com.hhy.dreamingrecall.archive.ArchiveManifest;
import com.hhy.dreamingrecall.archive.ContentAddressedStore;
import com.hhy.dreamingrecall.archive.ContentReference;
import com.hhy.dreamingrecall.archive.ContentReferenceCodec;
import com.hhy.dreamingrecall.archive.CoreRecordType;
import com.hhy.dreamingrecall.archive.ReplayRecord;
import com.hhy.dreamingrecall.archive.SegmentCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalArchiveDataSourceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesContentReferencesWithoutExposingStorageLayout() throws Exception {
        Path archive = archive("1.21.1");
        byte[] chunk = new byte[]{9, 8, 7, 6};
        ContentAddressedStore store = new ContentAddressedStore(archive, 1);
        ContentReference reference = store.put(chunk);
        SegmentCodec.commit(
                archive.resolve("segments"),
                0,
                List.of(ReplayRecord.core(
                        CoreRecordType.CHUNK_BASELINE,
                        10,
                        1,
                        "minecraft:overworld",
                        ContentReferenceCodec.encode(reference)
                )),
                1
        );

        try (LocalArchiveDataSource source = LocalArchiveDataSource.open(archive, "1.21.1")) {
            var segment = source.readSegment(source.index().segments().getFirst(), new ReadCancellation())
                    .get(5, TimeUnit.SECONDS);
            assertArrayEquals(chunk, segment.records().getFirst().payloadCopy());
        }
    }

    @Test
    void rejectsDifferentMinecraftVersion() throws Exception {
        Path archive = archive("1.21.1");
        SegmentCodec.commit(
                archive.resolve("segments"),
                0,
                List.of(ReplayRecord.control(CoreRecordType.SESSION_START, 0, 0, new byte[0])),
                1
        );

        IOException failure = assertThrows(IOException.class, () -> LocalArchiveDataSource.open(archive, "1.21.2"));
        assertEquals(true, failure.getMessage().contains("does not match"));
    }

    @Test
    void missingContentUsesIdentifiedChunkFallback() throws Exception {
        Path archive = archive("1.21.1");
        ContentReference missing = new ContentReference(new byte[32], 4096);
        byte[] fallback = new byte[]{2, 4, 6, 8};
        SegmentCodec.commit(
                archive.resolve("segments"),
                0,
                List.of(ReplayRecord.core(
                        CoreRecordType.CHUNK_BASELINE,
                        10,
                        1,
                        "minecraft:overworld",
                        ContentReferenceCodec.encode(missing, fallback)
                )),
                1
        );
        assertEquals(false, Files.exists(new ContentAddressedStore(archive, 1).path(missing)));

        try (LocalArchiveDataSource source = LocalArchiveDataSource.open(archive, "1.21.1")) {
            var segment = source.readSegment(source.index().segments().getFirst(), new ReadCancellation())
                    .get(5, TimeUnit.SECONDS);
            assertArrayEquals(fallback, segment.records().getFirst().payloadCopy());
        }
    }

    private Path archive(String minecraftVersion) throws Exception {
        return ArchiveLayout.initialize(
                temporaryDirectory,
                ArchiveManifest.create(minecraftVersion, "test", ArchiveManifest.SourceKind.SINGLEPLAYER)
        );
    }
}
