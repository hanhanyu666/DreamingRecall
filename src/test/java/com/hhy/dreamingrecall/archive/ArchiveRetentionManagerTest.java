package com.hhy.dreamingrecall.archive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveRetentionManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void onlyOldestAutomaticArchivesAreEvicted() throws Exception {
        Path root = temporaryDirectory.resolve("replays");
        Path automaticOld = archive(root, "auto-old", 100, true, 32);
        Path automaticNew = archive(root, "auto-new", 200, true, 32);
        Path manual = archive(root, "manual", 50, false, 64);

        ArchiveRetentionManager.RetentionResult result = ArchiveRetentionManager.enforce(
                root,
                new ArchiveRetentionManager.Policy(size(automaticNew), 0, 0)
        );

        assertEquals(1, result.deletedArchives());
        assertFalse(Files.exists(automaticOld));
        assertTrue(Files.isDirectory(automaticNew));
        assertTrue(Files.isDirectory(manual));
    }

    private static Path archive(Path root, String name, long created, boolean automatic, int bytes)
            throws Exception {
        Path directory = Files.createDirectories(root.resolve(name));
        ArchiveManifestCodec.writeManifest(directory, new ArchiveManifest(
                UUID.randomUUID(),
                ArchiveFormat.FORMAT_MAJOR,
                ArchiveFormat.FORMAT_MINOR,
                "1.21.1",
                "test",
                ArchiveManifest.SourceKind.DEDICATED_SERVER,
                created,
                automatic
        ));
        Files.write(directory.resolve("payload.bin"), new byte[bytes]);
        return directory;
    }

    private static long size(Path directory) throws Exception {
        try (var files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (java.io.IOException failure) {
                    throw new java.io.UncheckedIOException(failure);
                }
            }).sum();
        }
    }
}
