package com.hhy.dreamingrecall.director;

import java.util.Objects;

public record CameraPose(
        String dimensionId,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        float roll,
        float fov
) {
    public CameraPose {
        Objects.requireNonNull(dimensionId, "dimensionId");
        if (dimensionId.isBlank()) {
            throw new IllegalArgumentException("dimensionId cannot be blank");
        }
        if (!Double.isFinite(x)
                || !Double.isFinite(y)
                || !Double.isFinite(z)
                || !Float.isFinite(yaw)
                || !Float.isFinite(pitch)
                || !Float.isFinite(roll)
                || !Float.isFinite(fov)
                || fov <= 0.0F
                || fov >= 180.0F) {
            throw new IllegalArgumentException("Camera pose contains invalid numeric values");
        }
    }
}
