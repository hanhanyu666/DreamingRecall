package com.hhy.dreamingrecall.archive;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ArchiveManifestCodec {
    public static final String MANIFEST_FILE = "manifest.json";
    public static final String COMPLETION_FILE = "completion.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ArchiveManifestCodec() {
    }

    public static void writeManifest(Path archiveDirectory, ArchiveManifest manifest) throws IOException {
        writeJsonAtomic(archiveDirectory.resolve(MANIFEST_FILE), GSON.toJson(manifest));
    }

    public static ArchiveManifest readManifest(Path archiveDirectory) throws IOException {
        try {
            ArchiveManifest manifest = GSON.fromJson(
                    Files.readString(archiveDirectory.resolve(MANIFEST_FILE), StandardCharsets.UTF_8),
                    ArchiveManifest.class
            );
            if (manifest == null) {
                throw new IOException("Archive manifest is empty");
            }
            return manifest;
        } catch (JsonParseException | IllegalArgumentException failure) {
            throw new IOException("Invalid archive manifest", failure);
        }
    }

    public static void writeCompletion(Path archiveDirectory, ArchiveCompletion completion) throws IOException {
        writeJsonAtomic(archiveDirectory.resolve(COMPLETION_FILE), GSON.toJson(completion));
    }

    private static void writeJsonAtomic(Path target, String json) throws IOException {
        Files.createDirectories(target.getParent());
        Path partial = target.resolveSibling(target.getFileName() + ArchiveFormat.PARTIAL_EXTENSION);
        Files.writeString(partial, json, StandardCharsets.UTF_8);
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, target);
        }
    }
}
