package com.hhy.dreamingrecall.playback.state;

import com.hhy.dreamingrecall.playback.decode.DecodedPayload;

import java.util.Optional;
import java.util.UUID;

public final class ReplayMotionInterpolator {
    private static final double VANILLA_TICK_NANOS = 50_000_000.0;
    private static final long CLIENT_TRACK_STALE_NANOS = 500_000_000L;

    private ReplayMotionInterpolator() {
    }

    public static DecodedPayload.PlayerState playerAt(
            ReplayPlaybackFrame frame,
            UUID playerId,
            DecodedPayload.PlayerState fallback
    ) {
        ReplayPlaybackFrame.TimedPlayerState current = frame.currentPlayers().get(playerId);
        if (current == null) {
            return fallback;
        }
        ReplayPlaybackFrame.TimedPlayerState next = frame.nextPlayers().get(playerId);
        DecodedPayload.PlayerState serverState = next == null
                ? current.state()
                : interpolatePlayer(current, next, frame.snapshot().archiveNanos());

        Optional<DecodedPayload.ClientPlayerSample> clientSample = playerVisualAt(frame, playerId);
        if (clientSample.isEmpty()) {
            clientSample = cameraPlayerVisualAt(frame, playerId);
        }
        if (clientSample.isEmpty()) {
            return serverState;
        }
        DecodedPayload.ClientPlayerSample clientState = clientSample.orElseThrow();
        double eyeOffsetX = serverState.eyeX() - serverState.transform().x();
        double eyeOffsetY = serverState.eyeY() - serverState.transform().y();
        double eyeOffsetZ = serverState.eyeZ() - serverState.transform().z();
        return new DecodedPayload.PlayerState(
                serverState.uuid(),
                serverState.name(),
                clientState.transform(),
                clientState.transform().x() + eyeOffsetX,
                clientState.transform().y() + eyeOffsetY,
                clientState.transform().z() + eyeOffsetZ,
                clientState.headYaw(),
                clientState.bodyYaw(),
                serverState.health(),
                serverState.absorption(),
                serverState.foodLevel(),
                serverState.selectedSlot(),
                serverState.gameMode(),
                serverState.equipment(),
                Optional.of(clientState.animation())
        );
    }

    public static boolean hasClientPlayerVisual(ReplayPlaybackFrame frame, UUID playerId) {
        return playerVisualAt(frame, playerId).isPresent()
                || cameraPlayerVisualAt(frame, playerId).isPresent();
    }

    private static Optional<DecodedPayload.ClientPlayerSample> playerVisualAt(
            ReplayPlaybackFrame frame,
            UUID playerId
    ) {
        ReplayPlaybackFrame.TimedPlayerVisualSample current = frame.currentPlayerVisualSamples().get(playerId);
        if (current == null) {
            return Optional.empty();
        }
        ReplayPlaybackFrame.TimedPlayerVisualSample next = frame.nextPlayerVisualSamples().get(playerId);
        if (next == null && frame.snapshot().archiveNanos() - current.archiveNanos() > CLIENT_TRACK_STALE_NANOS) {
            return Optional.empty();
        }
        if (next == null || !current.dimensionId().equals(next.dimensionId())) {
            return Optional.of(current.sample().playerSample());
        }
        return Optional.of(interpolateClientPlayer(current, next, frame.snapshot().archiveNanos()));
    }

    private static Optional<DecodedPayload.ClientPlayerSample> cameraPlayerVisualAt(
            ReplayPlaybackFrame frame,
            UUID playerId
    ) {
        ReplayPlaybackFrame.TimedCameraSample currentCamera = frame.currentCameraSamples().get(playerId);
        if (currentCamera == null || currentCamera.sample().playerSample().isEmpty()) {
            return Optional.empty();
        }
        ReplayPlaybackFrame.TimedCameraSample nextCamera = frame.nextCameraSamples().get(playerId);
        if ((nextCamera == null || nextCamera.sample().playerSample().isEmpty())
                && frame.snapshot().archiveNanos() - currentCamera.archiveNanos() > CLIENT_TRACK_STALE_NANOS) {
            return Optional.empty();
        }
        DecodedPayload.ClientPlayerSample clientState = nextCamera == null
                || nextCamera.sample().playerSample().isEmpty()
                || !currentCamera.dimensionId().equals(nextCamera.dimensionId())
                ? currentCamera.sample().playerSample().orElseThrow()
                : interpolateClientPlayer(currentCamera, nextCamera, frame.snapshot().archiveNanos());
        return Optional.of(clientState);
    }

    public static Optional<DecodedPayload.CameraSample> cameraAt(
            ReplayPlaybackFrame frame,
            UUID playerId
    ) {
        ReplayPlaybackFrame.TimedCameraSample current = frame.currentCameraSamples().get(playerId);
        if (current == null) {
            return Optional.empty();
        }
        ReplayPlaybackFrame.TimedCameraSample next = frame.nextCameraSamples().get(playerId);
        if (next == null || !current.dimensionId().equals(next.dimensionId())) {
            return Optional.of(current.sample());
        }
        double alpha = fraction(current.archiveNanos(), next.archiveNanos(), frame.snapshot().archiveNanos());
        DecodedPayload.CameraSample a = current.sample();
        DecodedPayload.CameraSample b = next.sample();
        Optional<DecodedPayload.ClientPlayerSample> playerSample = a.playerSample().isPresent()
                && b.playerSample().isPresent()
                ? Optional.of(interpolateClientPlayer(current, next, frame.snapshot().archiveNanos()))
                : a.playerSample();
        return Optional.of(new DecodedPayload.CameraSample(
                a.playerId(),
                a.clientNanos(),
                lerp(a.x(), b.x(), alpha),
                lerp(a.y(), b.y(), alpha),
                lerp(a.z(), b.z(), alpha),
                lerpAngle(a.yaw(), b.yaw(), alpha),
                (float) lerp(a.pitch(), b.pitch(), alpha),
                lerpAngle(a.roll(), b.roll(), alpha),
                (float) lerp(a.fov(), b.fov(), alpha),
                playerSample
        ));
    }

    private static DecodedPayload.PlayerState interpolatePlayer(
            ReplayPlaybackFrame.TimedPlayerState current,
            ReplayPlaybackFrame.TimedPlayerState next,
            long targetNanos
    ) {
        if (!current.dimensionId().equals(next.dimensionId())) {
            return current.state();
        }
        double alpha = fraction(current.archiveNanos(), next.archiveNanos(), targetNanos);
        DecodedPayload.PlayerState a = current.state();
        DecodedPayload.PlayerState b = next.state();
        DecodedPayload.Transform transform = interpolateTransform(
                a.transform(),
                b.transform(),
                alpha,
                next.archiveNanos() - current.archiveNanos()
        );
        Optional<DecodedPayload.PlayerAnimation> animation = a.animation().isPresent() && b.animation().isPresent()
                ? Optional.of(interpolateAnimation(a.animation().orElseThrow(), b.animation().orElseThrow(), alpha))
                : a.animation();
        return new DecodedPayload.PlayerState(
                a.uuid(),
                a.name(),
                transform,
                lerp(a.eyeX(), b.eyeX(), alpha),
                lerp(a.eyeY(), b.eyeY(), alpha),
                lerp(a.eyeZ(), b.eyeZ(), alpha),
                lerpAngle(a.headYaw(), b.headYaw(), alpha),
                lerpAngle(a.bodyYaw(), b.bodyYaw(), alpha),
                (float) lerp(a.health(), b.health(), alpha),
                (float) lerp(a.absorption(), b.absorption(), alpha),
                a.foodLevel(),
                a.selectedSlot(),
                a.gameMode(),
                a.equipment(),
                animation
        );
    }

    private static DecodedPayload.ClientPlayerSample interpolateClientPlayer(
            ReplayPlaybackFrame.TimedCameraSample current,
            ReplayPlaybackFrame.TimedCameraSample next,
            long targetNanos
    ) {
        double alpha = fraction(current.archiveNanos(), next.archiveNanos(), targetNanos);
        DecodedPayload.ClientPlayerSample a = current.sample().playerSample().orElseThrow();
        DecodedPayload.ClientPlayerSample b = next.sample().playerSample().orElseThrow();
        return new DecodedPayload.ClientPlayerSample(
                interpolateTransform(
                        a.transform(),
                        b.transform(),
                        alpha,
                        next.archiveNanos() - current.archiveNanos()
                ),
                lerpAngle(a.headYaw(), b.headYaw(), alpha),
                lerpAngle(a.bodyYaw(), b.bodyYaw(), alpha),
                interpolateAnimation(a.animation(), b.animation(), alpha)
        );
    }

    private static DecodedPayload.ClientPlayerSample interpolateClientPlayer(
            ReplayPlaybackFrame.TimedPlayerVisualSample current,
            ReplayPlaybackFrame.TimedPlayerVisualSample next,
            long targetNanos
    ) {
        double alpha = fraction(current.archiveNanos(), next.archiveNanos(), targetNanos);
        DecodedPayload.ClientPlayerSample a = current.sample().playerSample();
        DecodedPayload.ClientPlayerSample b = next.sample().playerSample();
        return new DecodedPayload.ClientPlayerSample(
                interpolateTransform(
                        a.transform(),
                        b.transform(),
                        alpha,
                        next.archiveNanos() - current.archiveNanos()
                ),
                lerpAngle(a.headYaw(), b.headYaw(), alpha),
                lerpAngle(a.bodyYaw(), b.bodyYaw(), alpha),
                interpolateAnimation(a.animation(), b.animation(), alpha)
        );
    }

    private static DecodedPayload.Transform interpolateTransform(
            DecodedPayload.Transform a,
            DecodedPayload.Transform b,
            double alpha,
            long intervalNanos
    ) {
        double velocityScale = intervalNanos <= 0 ? 0.0 : VANILLA_TICK_NANOS / intervalNanos;
        return new DecodedPayload.Transform(
                lerp(a.x(), b.x(), alpha),
                lerp(a.y(), b.y(), alpha),
                lerp(a.z(), b.z(), alpha),
                lerpAngle(a.yaw(), b.yaw(), alpha),
                (float) lerp(a.pitch(), b.pitch(), alpha),
                (b.x() - a.x()) * velocityScale,
                (b.y() - a.y()) * velocityScale,
                (b.z() - a.z()) * velocityScale,
                alpha < 1.0 ? a.pose() : b.pose(),
                alpha < 1.0 ? a.onGround() : b.onGround()
        );
    }

    private static DecodedPayload.PlayerAnimation interpolateAnimation(
            DecodedPayload.PlayerAnimation a,
            DecodedPayload.PlayerAnimation b,
            double alpha
    ) {
        return new DecodedPayload.PlayerAnimation(
                (float) lerp(a.walkPosition(), b.walkPosition(), alpha),
                (float) lerp(a.walkSpeed(), b.walkSpeed(), alpha),
                (float) lerp(a.attackProgress(), b.attackProgress(), alpha),
                a.swinging(),
                a.swingTime(),
                a.swingingArm(),
                a.usingItem(),
                a.usedItemHand(),
                a.useItemRemainingTicks(),
                (float) lerp(a.swimAmount(), b.swimAmount(), alpha),
                a.fallFlyingTicks()
        );
    }

    private static double fraction(long start, long end, long target) {
        if (end <= start) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (double) (target - start) / (double) (end - start)));
    }

    private static double lerp(double a, double b, double alpha) {
        return a + (b - a) * alpha;
    }

    private static float lerpAngle(float a, float b, double alpha) {
        float delta = (b - a) % 360.0F;
        if (delta < -180.0F) {
            delta += 360.0F;
        } else if (delta >= 180.0F) {
            delta -= 360.0F;
        }
        return a + delta * (float) alpha;
    }
}
