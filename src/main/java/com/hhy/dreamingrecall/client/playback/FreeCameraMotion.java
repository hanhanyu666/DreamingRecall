package com.hhy.dreamingrecall.client.playback;

import net.minecraft.client.Camera;
import org.joml.Vector3f;

/** Computes horizontal free-camera movement from the direction visible on screen. */
public final class FreeCameraMotion {
    private static final double MIN_HORIZONTAL_LENGTH_SQUARED = 1.0E-8;

    private FreeCameraMotion() {
    }

    public static Delta horizontalDelta(
            Camera camera,
            float fallbackYaw,
            double forward,
            double strafe,
            double distance
    ) {
        if (camera != null && camera.isInitialized()) {
            Vector3f look = camera.getLookVector();
            return horizontalDelta(look.x(), look.z(), fallbackYaw, forward, strafe, distance);
        }
        return horizontalDelta(fallbackYaw, forward, strafe, distance);
    }

    static Delta horizontalDelta(
            double lookX,
            double lookZ,
            float fallbackYaw,
            double forward,
            double strafe,
            double distance
    ) {
        double lengthSquared = lookX * lookX + lookZ * lookZ;
        if (lengthSquared <= MIN_HORIZONTAL_LENGTH_SQUARED) {
            return horizontalDelta(fallbackYaw, forward, strafe, distance);
        }
        double length = Math.sqrt(lengthSquared);
        double forwardX = lookX / length;
        double forwardZ = lookZ / length;
        return horizontalDelta(forwardX, forwardZ, forward, strafe, distance);
    }

    public static Delta horizontalDelta(
            float yaw,
            double forward,
            double strafe,
            double distance
    ) {
        double radians = Math.toRadians(yaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        return horizontalDelta(forwardX, forwardZ, forward, strafe, distance);
    }

    private static Delta horizontalDelta(
            double forwardX,
            double forwardZ,
            double forward,
            double strafe,
            double distance
    ) {
        // The input convention is positive strafe = left, matching Minecraft's key bindings.
        double leftX = forwardZ;
        double leftZ = -forwardX;
        return new Delta(
                (forwardX * forward + leftX * strafe) * distance,
                (forwardZ * forward + leftZ * strafe) * distance
        );
    }

    public record Delta(double x, double z) {
    }
}
