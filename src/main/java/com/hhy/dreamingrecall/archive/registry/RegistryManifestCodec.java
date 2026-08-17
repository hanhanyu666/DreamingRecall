package com.hhy.dreamingrecall.archive.registry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hhy.dreamingrecall.archive.ArchiveFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class RegistryManifestCodec {
    public static final String FILE_NAME = "registries.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private RegistryManifestCodec() {
    }

    public static void write(Path archiveDirectory, RegistryManifest manifest) throws IOException {
        Path target = archiveDirectory.resolve(FILE_NAME);
        Path partial = target.resolveSibling(target.getFileName() + ArchiveFormat.PARTIAL_EXTENSION);
        Files.writeString(partial, GSON.toJson(manifest), StandardCharsets.UTF_8);
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static RegistryManifest read(Path archiveDirectory) throws IOException {
        try {
            RegistryManifest parsed = GSON.fromJson(
                    Files.readString(archiveDirectory.resolve(FILE_NAME), StandardCharsets.UTF_8),
                    RegistryManifest.class
            );
            if (parsed == null) {
                throw new IOException("Registry manifest is empty");
            }
            return new RegistryManifest(
                    parsed.schemaVersion(),
                    parsed.minecraftVersion(),
                    parsed.neoForgeVersion(),
                    parsed.mods(),
                    parsed.registries(),
                    parsed.fingerprint()
            );
        } catch (RuntimeException failure) {
            throw new IOException("Invalid registry manifest", failure);
        }
    }
}
