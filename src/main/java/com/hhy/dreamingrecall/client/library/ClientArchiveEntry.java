package com.hhy.dreamingrecall.client.library;

import com.hhy.dreamingrecall.archive.ArchiveManifest;

import java.nio.file.Path;
import java.util.Objects;

public record ClientArchiveEntry(
        Path directory,
        ArchiveManifest manifest,
        String sourceLabel,
        boolean complete,
        long durationNanos,
        int segmentCount,
        int errorCount,
        int warningCount
) {
    public ClientArchiveEntry {
        directory = directory.toAbsolutePath().normalize();
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(sourceLabel, "sourceLabel");
        if (durationNanos < 0 || segmentCount < 0 || errorCount < 0 || warningCount < 0) {
            throw new IllegalArgumentException("Invalid client archive summary");
        }
    }

    public String displayName() {
        return directory.getFileName().toString();
    }
}
