package com.hhy.dreamingrecall.archive.track;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hhy.dreamingrecall.archive.ArchiveFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class TrackCatalogCodec {
    public static final String FILE_NAME = "tracks.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private TrackCatalogCodec() {
    }

    public static void write(Path archiveDirectory, TrackCatalog catalog) throws IOException {
        Path target = archiveDirectory.resolve(FILE_NAME);
        Path partial = target.resolveSibling(target.getFileName() + ArchiveFormat.PARTIAL_EXTENSION);
        Files.createDirectories(archiveDirectory);
        Files.writeString(partial, GSON.toJson(catalog), StandardCharsets.UTF_8);
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static TrackCatalog read(Path archiveDirectory) throws IOException {
        try {
            TrackCatalog catalog = GSON.fromJson(
                    Files.readString(archiveDirectory.resolve(FILE_NAME), StandardCharsets.UTF_8),
                    TrackCatalog.class
            );
            if (catalog == null) {
                throw new IOException("Track catalog is empty");
            }
            return new TrackCatalog(catalog.schemaVersion(), catalog.families());
        } catch (RuntimeException failure) {
            throw new IOException("Invalid track catalog", failure);
        }
    }
}
