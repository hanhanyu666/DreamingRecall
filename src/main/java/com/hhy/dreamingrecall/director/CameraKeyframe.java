package com.hhy.dreamingrecall.director;

import java.util.Objects;
import java.util.UUID;

public record CameraKeyframe(
        UUID id,
        long archiveNanos,
        CameraPose pose,
        CameraInterpolation interpolationToNext
) {
    public CameraKeyframe {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(interpolationToNext, "interpolationToNext");
        if (archiveNanos < 0) {
            throw new IllegalArgumentException("archiveNanos must be non-negative");
        }
    }

    public static CameraKeyframe create(
            long archiveNanos,
            CameraPose pose,
            CameraInterpolation interpolationToNext
    ) {
        return new CameraKeyframe(UUID.randomUUID(), archiveNanos, pose, interpolationToNext);
    }
}
