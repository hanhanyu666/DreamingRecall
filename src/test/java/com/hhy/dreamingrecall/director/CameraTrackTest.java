package com.hhy.dreamingrecall.director;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CameraTrackTest {
    @Test
    void interpolatesPositionFovAndShortestYawPath() {
        CameraTrack track = new CameraTrack(List.of(
                keyframe(0, pose("minecraft:overworld", 0, 350, 70), CameraInterpolation.LINEAR),
                keyframe(100, pose("minecraft:overworld", 100, 10, 90), CameraInterpolation.LINEAR)
        ));

        CameraPose middle = track.evaluate(50).orElseThrow();
        assertEquals(50.0, middle.x(), 0.0001);
        assertEquals(80.0, middle.fov(), 0.0001);
        assertEquals(0.0, normalizeDegrees(middle.yaw()), 0.0001);
    }

    @Test
    void smoothInterpolationUsesSmoothstepAndDimensionChangesAreHardCuts() {
        CameraKeyframe first = keyframe(
                0,
                pose("minecraft:overworld", 0, 0, 70),
                CameraInterpolation.SMOOTH
        );
        CameraKeyframe second = keyframe(
                100,
                pose("minecraft:overworld", 100, 0, 70),
                CameraInterpolation.LINEAR
        );
        CameraKeyframe third = keyframe(
                200,
                pose("minecraft:the_nether", 500, 0, 70),
                CameraInterpolation.LINEAR
        );
        CameraTrack track = new CameraTrack(List.of(first, second, third));

        assertEquals(15.625, track.evaluate(25).orElseThrow().x(), 0.0001);
        assertEquals("minecraft:overworld", track.evaluate(199).orElseThrow().dimensionId());
        assertEquals(100.0, track.evaluate(199).orElseThrow().x(), 0.0001);
        assertEquals("minecraft:the_nether", track.evaluate(200).orElseThrow().dimensionId());
    }

    @Test
    void movingAKeyframeReordersTheTrackAndRejectsTimeCollisions() {
        CameraKeyframe first = keyframe(10, pose("minecraft:overworld", 10, 0, 70), CameraInterpolation.LINEAR);
        CameraKeyframe second = keyframe(20, pose("minecraft:overworld", 20, 0, 70), CameraInterpolation.LINEAR);
        CameraTrack moved = new CameraTrack(List.of(first, second)).move(second.id(), 5);

        assertEquals(second.id(), moved.keyframes().getFirst().id());
        assertThrows(IllegalArgumentException.class, () -> moved.move(first.id(), 5));
    }

    private static CameraKeyframe keyframe(
            long time,
            CameraPose pose,
            CameraInterpolation interpolation
    ) {
        return new CameraKeyframe(UUID.randomUUID(), time, pose, interpolation);
    }

    private static CameraPose pose(String dimension, double x, float yaw, float fov) {
        return new CameraPose(dimension, x, 64, 0, yaw, 0, 0, fov);
    }

    private static float normalizeDegrees(float value) {
        float normalized = value % 360.0F;
        return normalized < 0 ? normalized + 360.0F : normalized;
    }
}
