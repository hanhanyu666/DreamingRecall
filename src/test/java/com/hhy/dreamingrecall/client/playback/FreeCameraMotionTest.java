package com.hhy.dreamingrecall.client.playback;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FreeCameraMotionTest {
    @Test
    void usesCameraLookDirectionForForwardAndStrafe() {
        // A camera looking west (negative X) moves W west and A south.
        FreeCameraMotion.Delta delta = FreeCameraMotion.horizontalDelta(
                -2.0,
                0.0,
                0.0F,
                1.0,
                1.0,
                2.0
        );

        assertEquals(-2.0, delta.x(), 1.0E-9);
        assertEquals(2.0, delta.z(), 1.0E-9);
    }

    @Test
    void fallsBackToYawWhenTheCameraLooksStraightUpOrDown() {
        FreeCameraMotion.Delta delta = FreeCameraMotion.horizontalDelta(
                0.0,
                0.0,
                180.0F,
                1.0,
                0.0,
                3.0
        );

        assertEquals(0.0, delta.x(), 1.0E-9);
        assertEquals(-3.0, delta.z(), 1.0E-9);
    }
}
