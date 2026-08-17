package com.hhy.dreamingrecall.director;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DirectorProjectCodecTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void projectRoundTripsAndCanBeAtomicallyOverwritten() throws Exception {
        UUID archiveId = UUID.randomUUID();
        CameraKeyframe first = CameraKeyframe.create(
                1_000,
                new CameraPose("minecraft:overworld", 1, 2, 3, 10, 20, 0, 70),
                CameraInterpolation.SMOOTH
        );
        CameraKeyframe second = CameraKeyframe.create(
                2_000,
                new CameraPose("minecraft:overworld", 4, 5, 6, 30, 40, 5, 80),
                CameraInterpolation.LINEAR
        );
        DirectorProject project = DirectorProject.create(archiveId, "Opening shot")
                .withCameraTrack(new CameraTrack(java.util.List.of(first, second)))
                .withMarker(DirectorMarker.create(1_500, "Beat", 0xFF4AA3FF))
                .withPlaybackRange(Optional.of(new PlaybackRange(900, 2_100)))
                .withPlaybackSpeed(0.75);
        Path file = temporaryDirectory.resolve("opening" + DirectorProjectCodec.FILE_EXTENSION);

        DirectorProjectCodec.write(file, project);
        assertEquals(project, DirectorProjectCodec.read(file));

        DirectorProject updated = project.withPlaybackSpeed(2.0);
        DirectorProjectCodec.write(file, updated);
        assertEquals(updated, DirectorProjectCodec.read(file));
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".partial")));
    }

    @Test
    void rejectsUnsupportedProjectSchema() throws Exception {
        Path file = temporaryDirectory.resolve("future.drdirector");
        Files.writeString(file, "{\"schema\":99}");

        assertThrows(java.io.IOException.class, () -> DirectorProjectCodec.read(file));
    }
}
