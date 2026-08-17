package com.hhy.dreamingrecall.director;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record DirectorProject(
        UUID projectId,
        UUID archiveId,
        String name,
        long createdEpochMillis,
        long modifiedEpochMillis,
        CameraTrack cameraTrack,
        List<DirectorMarker> markers,
        Optional<PlaybackRange> playbackRange,
        double playbackSpeed
) {
    public DirectorProject {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(archiveId, "archiveId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(cameraTrack, "cameraTrack");
        Objects.requireNonNull(playbackRange, "playbackRange");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Project name cannot be blank");
        }
        if (createdEpochMillis < 0 || modifiedEpochMillis < createdEpochMillis) {
            throw new IllegalArgumentException("Invalid director project timestamps");
        }
        if (!Double.isFinite(playbackSpeed) || playbackSpeed <= 0.0 || playbackSpeed > 16.0) {
            throw new IllegalArgumentException("Playback speed must be in (0, 16]");
        }
        ArrayList<DirectorMarker> sortedMarkers = new ArrayList<>(markers);
        sortedMarkers.sort(Comparator.comparingLong(DirectorMarker::archiveNanos));
        markers = List.copyOf(sortedMarkers);
    }

    public static DirectorProject create(UUID archiveId, String name) {
        long now = Instant.now().toEpochMilli();
        return new DirectorProject(
                UUID.randomUUID(),
                archiveId,
                name,
                now,
                now,
                CameraTrack.empty(),
                List.of(),
                Optional.empty(),
                1.0
        );
    }

    public DirectorProject withCameraTrack(CameraTrack track) {
        return copy(track, markers, playbackRange, playbackSpeed);
    }

    public DirectorProject withMarker(DirectorMarker marker) {
        ArrayList<DirectorMarker> updated = new ArrayList<>(markers);
        updated.removeIf(existing -> existing.id().equals(marker.id()));
        updated.add(marker);
        return copy(cameraTrack, updated, playbackRange, playbackSpeed);
    }

    public DirectorProject withoutMarker(UUID markerId) {
        ArrayList<DirectorMarker> updated = new ArrayList<>(markers);
        if (!updated.removeIf(marker -> marker.id().equals(markerId))) {
            throw new java.util.NoSuchElementException("Unknown director marker " + markerId);
        }
        return copy(cameraTrack, updated, playbackRange, playbackSpeed);
    }

    public DirectorProject withPlaybackRange(Optional<PlaybackRange> range) {
        return copy(cameraTrack, markers, range, playbackSpeed);
    }

    public DirectorProject withPlaybackSpeed(double speed) {
        return copy(cameraTrack, markers, playbackRange, speed);
    }

    private DirectorProject copy(
            CameraTrack track,
            List<DirectorMarker> updatedMarkers,
            Optional<PlaybackRange> range,
            double speed
    ) {
        return new DirectorProject(
                projectId,
                archiveId,
                name,
                createdEpochMillis,
                Math.max(modifiedEpochMillis, Instant.now().toEpochMilli()),
                track,
                updatedMarkers,
                range,
                speed
        );
    }
}
