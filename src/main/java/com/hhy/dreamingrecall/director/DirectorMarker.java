package com.hhy.dreamingrecall.director;

import java.util.Objects;
import java.util.UUID;

public record DirectorMarker(UUID id, long archiveNanos, String label, int colorArgb) {
    public DirectorMarker {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        if (archiveNanos < 0) {
            throw new IllegalArgumentException("archiveNanos must be non-negative");
        }
    }

    public static DirectorMarker create(long archiveNanos, String label, int colorArgb) {
        return new DirectorMarker(UUID.randomUUID(), archiveNanos, label, colorArgb);
    }
}
