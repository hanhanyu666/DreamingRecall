package com.hhy.dreamingrecall.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public final class ArchiveLayout {
    private static final DateTimeFormatter DIRECTORY_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd_HH-mm-ss")
            .withZone(ZoneOffset.UTC);

    private ArchiveLayout() {
    }

    public static Path initialize(Path archiveRoot, ArchiveManifest manifest) throws IOException {
        String shortId = manifest.archiveId().toString().substring(0, 8);
        String directoryName = DIRECTORY_TIME.format(Instant.ofEpochMilli(manifest.createdEpochMillis())) + "_" + shortId;
        Path archiveDirectory = archiveRoot.resolve(directoryName);
        Files.createDirectories(archiveDirectory.resolve("segments"));
        Files.createDirectories(archiveDirectory.resolve("checkpoints"));
        Files.createDirectories(archiveDirectory.resolve("content"));
        Files.createDirectories(archiveDirectory.resolve("attachments"));
        ArchiveManifestCodec.writeManifest(archiveDirectory, manifest);
        return archiveDirectory;
    }
}
