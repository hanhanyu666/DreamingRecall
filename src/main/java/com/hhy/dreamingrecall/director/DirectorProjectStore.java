package com.hhy.dreamingrecall.director;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** File-system boundary for client-owned director projects. */
public final class DirectorProjectStore {
    private static final String DIRECTORY = "directors";

    private DirectorProjectStore() {
    }

    public static Path root(Path gameDirectory) {
        return gameDirectory.toAbsolutePath().normalize()
                .resolve("dreamingrecall")
                .resolve(DIRECTORY);
    }

    public static Path path(Path gameDirectory, UUID archiveId) {
        Objects.requireNonNull(archiveId, "archiveId");
        return root(gameDirectory).resolve(archiveId + DirectorProjectCodec.FILE_EXTENSION);
    }

    /**
     * A missing project is normal on first use. A corrupt project is surfaced to
     * the caller instead of silently overwriting the user's edits.
     */
    public static DirectorProject loadOrCreate(Path gameDirectory, UUID archiveId, String defaultName)
            throws IOException {
        Path target = path(gameDirectory, archiveId);
        if (Files.isRegularFile(target)) {
            DirectorProject project = DirectorProjectCodec.read(target);
            if (!project.archiveId().equals(archiveId)) {
                throw new IOException("Director project references a different archive");
            }
            return project;
        }
        return DirectorProject.create(archiveId, defaultName);
    }

    public static void save(Path gameDirectory, DirectorProject project) throws IOException {
        Objects.requireNonNull(project, "project");
        DirectorProjectCodec.write(path(gameDirectory, project.archiveId()), project);
    }
}
