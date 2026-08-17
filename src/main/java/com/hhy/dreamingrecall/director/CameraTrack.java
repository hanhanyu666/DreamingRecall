package com.hhy.dreamingrecall.director;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

public final class CameraTrack {
    private final List<CameraKeyframe> keyframes;

    public CameraTrack(List<CameraKeyframe> keyframes) {
        ArrayList<CameraKeyframe> sorted = new ArrayList<>(keyframes);
        sorted.sort(Comparator.comparingLong(CameraKeyframe::archiveNanos));
        long previousTime = -1;
        java.util.HashSet<UUID> ids = new java.util.HashSet<>();
        for (CameraKeyframe keyframe : sorted) {
            if (keyframe.archiveNanos() == previousTime) {
                throw new IllegalArgumentException("Camera keyframes cannot share the same archive time");
            }
            if (!ids.add(keyframe.id())) {
                throw new IllegalArgumentException("Duplicate camera keyframe id " + keyframe.id());
            }
            previousTime = keyframe.archiveNanos();
        }
        this.keyframes = List.copyOf(sorted);
    }

    public static CameraTrack empty() {
        return new CameraTrack(List.of());
    }

    public List<CameraKeyframe> keyframes() {
        return keyframes;
    }

    public CameraTrack add(CameraKeyframe keyframe) {
        ArrayList<CameraKeyframe> updated = new ArrayList<>(keyframes);
        updated.add(keyframe);
        return new CameraTrack(updated);
    }

    public CameraTrack update(CameraKeyframe keyframe) {
        ArrayList<CameraKeyframe> updated = new ArrayList<>(keyframes);
        int index = indexOf(keyframe.id());
        updated.set(index, keyframe);
        return new CameraTrack(updated);
    }

    public CameraTrack move(UUID id, long archiveNanos) {
        if (archiveNanos < 0) {
            throw new IllegalArgumentException("archiveNanos must be non-negative");
        }
        CameraKeyframe existing = keyframes.get(indexOf(id));
        return update(new CameraKeyframe(
                existing.id(),
                archiveNanos,
                existing.pose(),
                existing.interpolationToNext()
        ));
    }

    public CameraTrack remove(UUID id) {
        int index = indexOf(id);
        ArrayList<CameraKeyframe> updated = new ArrayList<>(keyframes);
        updated.remove(index);
        return new CameraTrack(updated);
    }

    public Optional<CameraPose> evaluate(long archiveNanos) {
        if (archiveNanos < 0) {
            throw new IllegalArgumentException("archiveNanos must be non-negative");
        }
        if (keyframes.isEmpty()) {
            return Optional.empty();
        }
        if (archiveNanos <= keyframes.getFirst().archiveNanos()) {
            return Optional.of(keyframes.getFirst().pose());
        }
        if (archiveNanos >= keyframes.getLast().archiveNanos()) {
            return Optional.of(keyframes.getLast().pose());
        }

        int low = 0;
        int high = keyframes.size() - 1;
        int leftIndex = 0;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (keyframes.get(middle).archiveNanos() <= archiveNanos) {
                leftIndex = middle;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        CameraKeyframe left = keyframes.get(leftIndex);
        CameraKeyframe right = keyframes.get(leftIndex + 1);
        if (!left.pose().dimensionId().equals(right.pose().dimensionId())) {
            return Optional.of(left.pose());
        }
        double alpha = (double) (archiveNanos - left.archiveNanos())
                / (double) (right.archiveNanos() - left.archiveNanos());
        if (left.interpolationToNext() == CameraInterpolation.SMOOTH) {
            alpha = alpha * alpha * (3.0 - 2.0 * alpha);
        }
        return Optional.of(interpolate(left.pose(), right.pose(), alpha));
    }

    public List<CameraPose> samplePath(long startArchiveNanos, long endArchiveNanos, int samples) {
        if (startArchiveNanos < 0 || endArchiveNanos < startArchiveNanos || samples < 2 || samples > 100_000) {
            throw new IllegalArgumentException("Invalid camera path sampling request");
        }
        ArrayList<CameraPose> path = new ArrayList<>(samples);
        long duration = endArchiveNanos - startArchiveNanos;
        for (int index = 0; index < samples; index++) {
            long time = startArchiveNanos + Math.round((double) duration * index / (samples - 1));
            evaluate(time).ifPresent(path::add);
        }
        return List.copyOf(path);
    }

    private int indexOf(UUID id) {
        for (int index = 0; index < keyframes.size(); index++) {
            if (keyframes.get(index).id().equals(id)) {
                return index;
            }
        }
        throw new NoSuchElementException("Unknown camera keyframe " + id);
    }

    private static CameraPose interpolate(CameraPose left, CameraPose right, double alpha) {
        return new CameraPose(
                left.dimensionId(),
                lerp(left.x(), right.x(), alpha),
                lerp(left.y(), right.y(), alpha),
                lerp(left.z(), right.z(), alpha),
                lerpAngle(left.yaw(), right.yaw(), alpha),
                (float) lerp(left.pitch(), right.pitch(), alpha),
                lerpAngle(left.roll(), right.roll(), alpha),
                (float) lerp(left.fov(), right.fov(), alpha)
        );
    }

    private static double lerp(double left, double right, double alpha) {
        return left + (right - left) * alpha;
    }

    private static float lerpAngle(float left, float right, double alpha) {
        float delta = (right - left) % 360.0F;
        if (delta >= 180.0F) {
            delta -= 360.0F;
        } else if (delta < -180.0F) {
            delta += 360.0F;
        }
        return (float) (left + delta * alpha);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CameraTrack that && keyframes.equals(that.keyframes);
    }

    @Override
    public int hashCode() {
        return keyframes.hashCode();
    }

    @Override
    public String toString() {
        return "CameraTrack" + keyframes;
    }
}
