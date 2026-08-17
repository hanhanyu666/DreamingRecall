package com.hhy.dreamingrecall.director;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class DirectorProjectCodec {
    public static final String FILE_EXTENSION = ".drdirector";
    private static final int SCHEMA = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private DirectorProjectCodec() {
    }

    public static void write(Path target, DirectorProject project) throws IOException {
        ProjectDocument document = ProjectDocument.from(project);
        Path normalized = target.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        if (parent == null) {
            throw new IOException("Director project target has no parent directory");
        }
        Files.createDirectories(parent);
        Path partial = normalized.resolveSibling(normalized.getFileName() + ".partial");
        Files.writeString(partial, GSON.toJson(document), StandardCharsets.UTF_8);
        try {
            Files.move(
                    partial,
                    normalized,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, normalized, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static DirectorProject read(Path source) throws IOException {
        try {
            ProjectDocument document = GSON.fromJson(
                    Files.readString(source.toAbsolutePath().normalize(), StandardCharsets.UTF_8),
                    ProjectDocument.class
            );
            if (document == null || document.schema() != SCHEMA) {
                throw new IOException("Unsupported or empty director project");
            }
            return document.toProject();
        } catch (JsonParseException | IllegalArgumentException | NullPointerException failure) {
            throw new IOException("Invalid director project", failure);
        }
    }

    private record ProjectDocument(
            int schema,
            String projectId,
            String archiveId,
            String name,
            long createdEpochMillis,
            long modifiedEpochMillis,
            List<KeyframeDocument> cameraKeyframes,
            List<MarkerDocument> markers,
            RangeDocument playbackRange,
            double playbackSpeed
    ) {
        private static ProjectDocument from(DirectorProject project) {
            return new ProjectDocument(
                    SCHEMA,
                    project.projectId().toString(),
                    project.archiveId().toString(),
                    project.name(),
                    project.createdEpochMillis(),
                    project.modifiedEpochMillis(),
                    project.cameraTrack().keyframes().stream().map(KeyframeDocument::from).toList(),
                    project.markers().stream().map(MarkerDocument::from).toList(),
                    project.playbackRange().map(RangeDocument::from).orElse(null),
                    project.playbackSpeed()
            );
        }

        private DirectorProject toProject() {
            return new DirectorProject(
                    UUID.fromString(projectId),
                    UUID.fromString(archiveId),
                    name,
                    createdEpochMillis,
                    modifiedEpochMillis,
                    new CameraTrack(cameraKeyframes.stream().map(KeyframeDocument::toKeyframe).toList()),
                    markers.stream().map(MarkerDocument::toMarker).toList(),
                    Optional.ofNullable(playbackRange).map(RangeDocument::toRange),
                    playbackSpeed
            );
        }
    }

    private record KeyframeDocument(
            String id,
            long archiveNanos,
            String dimensionId,
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            float roll,
            float fov,
            CameraInterpolation interpolationToNext
    ) {
        private static KeyframeDocument from(CameraKeyframe keyframe) {
            CameraPose pose = keyframe.pose();
            return new KeyframeDocument(
                    keyframe.id().toString(),
                    keyframe.archiveNanos(),
                    pose.dimensionId(),
                    pose.x(),
                    pose.y(),
                    pose.z(),
                    pose.yaw(),
                    pose.pitch(),
                    pose.roll(),
                    pose.fov(),
                    keyframe.interpolationToNext()
            );
        }

        private CameraKeyframe toKeyframe() {
            return new CameraKeyframe(
                    UUID.fromString(id),
                    archiveNanos,
                    new CameraPose(dimensionId, x, y, z, yaw, pitch, roll, fov),
                    interpolationToNext
            );
        }
    }

    private record MarkerDocument(String id, long archiveNanos, String label, int colorArgb) {
        private static MarkerDocument from(DirectorMarker marker) {
            return new MarkerDocument(
                    marker.id().toString(),
                    marker.archiveNanos(),
                    marker.label(),
                    marker.colorArgb()
            );
        }

        private DirectorMarker toMarker() {
            return new DirectorMarker(UUID.fromString(id), archiveNanos, label, colorArgb);
        }
    }

    private record RangeDocument(long startArchiveNanos, long endArchiveNanos) {
        private static RangeDocument from(PlaybackRange range) {
            return new RangeDocument(range.startArchiveNanos(), range.endArchiveNanos());
        }

        private PlaybackRange toRange() {
            return new PlaybackRange(startArchiveNanos, endArchiveNanos);
        }
    }
}
