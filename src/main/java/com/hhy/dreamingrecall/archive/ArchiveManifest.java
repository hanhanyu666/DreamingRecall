package com.hhy.dreamingrecall.archive;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ArchiveManifest(
        UUID archiveId,
        int formatMajor,
        int formatMinor,
        String minecraftVersion,
        String recorderVersion,
        SourceKind sourceKind,
        long createdEpochMillis,
        boolean automatic
) {
    public ArchiveManifest(
            UUID archiveId,
            int formatMajor,
            int formatMinor,
            String minecraftVersion,
            String recorderVersion,
            SourceKind sourceKind,
            long createdEpochMillis
    ) {
        this(archiveId, formatMajor, formatMinor, minecraftVersion, recorderVersion, sourceKind, createdEpochMillis, false);
    }

    public ArchiveManifest {
        Objects.requireNonNull(archiveId, "archiveId");
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        Objects.requireNonNull(recorderVersion, "recorderVersion");
        Objects.requireNonNull(sourceKind, "sourceKind");
        if (formatMajor <= 0 || formatMinor < 0 || createdEpochMillis < 0) {
            throw new IllegalArgumentException("Invalid archive manifest values");
        }
    }

    public static ArchiveManifest create(String minecraftVersion, String recorderVersion, SourceKind sourceKind) {
        return create(minecraftVersion, recorderVersion, sourceKind, false);
    }

    public static ArchiveManifest create(
            String minecraftVersion,
            String recorderVersion,
            SourceKind sourceKind,
            boolean automatic
    ) {
        return new ArchiveManifest(
                UUID.randomUUID(),
                ArchiveFormat.FORMAT_MAJOR,
                ArchiveFormat.FORMAT_MINOR,
                minecraftVersion,
                recorderVersion,
                sourceKind,
                Instant.now().toEpochMilli(),
                automatic
        );
    }

    public enum SourceKind {
        SINGLEPLAYER,
        DEDICATED_SERVER,
        CLIENT_MULTIPLAYER
    }
}
