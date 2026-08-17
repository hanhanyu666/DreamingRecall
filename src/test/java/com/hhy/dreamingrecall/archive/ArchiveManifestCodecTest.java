package com.hhy.dreamingrecall.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveManifestCodecTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void initializesReadableArchiveIdentity() throws Exception {
        ArchiveManifest manifest = ArchiveManifest.create(
                "1.21.1",
                "test",
                ArchiveManifest.SourceKind.DEDICATED_SERVER
        );

        Path archive = ArchiveLayout.initialize(temporaryDirectory, manifest);

        assertTrue(Files.isDirectory(archive.resolve("segments")));
        assertTrue(Files.isDirectory(archive.resolve("attachments")));
        assertEquals(manifest, ArchiveManifestCodec.readManifest(archive));
    }
}
