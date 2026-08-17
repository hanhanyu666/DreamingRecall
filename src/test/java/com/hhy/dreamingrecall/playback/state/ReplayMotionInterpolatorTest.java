package com.hhy.dreamingrecall.playback.state;

import com.hhy.dreamingrecall.playback.decode.DecodedPayload;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayMotionInterpolatorTest {
    private static final String DIMENSION = "minecraft:overworld";

    @Test
    void interpolatesLowTpsSamplesByRealArchiveTimeAndUsesShortestRotation() {
        UUID playerId = UUID.randomUUID();
        long interval = 66_666_666L;
        long target = interval / 2;
        DecodedPayload.PlayerState start = player(playerId, 0.0, 170.0F, Optional.empty());
        DecodedPayload.PlayerState end = player(playerId, 3.0, -170.0F, Optional.empty());
        ReplayPlaybackFrame frame = frame(
                target,
                Map.of(playerId, new ReplayPlaybackFrame.TimedPlayerState(0, DIMENSION, start)),
                Map.of(playerId, new ReplayPlaybackFrame.TimedPlayerState(interval, DIMENSION, end)),
                Map.of(),
                Map.of()
        );

        DecodedPayload.PlayerState interpolated = ReplayMotionInterpolator.playerAt(frame, playerId, start);

        assertEquals(1.5, interpolated.transform().x(), 1.0E-6);
        assertEquals(2.25, interpolated.transform().velocityX(), 1.0E-6);
        assertEquals(180.0F, interpolated.transform().yaw(), 1.0E-4F);
    }

    @Test
    void clientVisualTrackOverridesServerMotionAndAnimation() {
        UUID playerId = UUID.randomUUID();
        DecodedPayload.PlayerState serverStart = player(playerId, 0.0, 0.0F, Optional.empty());
        DecodedPayload.PlayerState serverEnd = player(playerId, 10.0, 0.0F, Optional.empty());
        DecodedPayload.PlayerAnimation clientAnimation = animation(0.75F);
        DecodedPayload.CameraSample clientStart = camera(playerId, 40.0, clientAnimation);
        DecodedPayload.CameraSample clientEnd = camera(playerId, 60.0, clientAnimation);
        ReplayPlaybackFrame frame = frame(
                50,
                Map.of(playerId, new ReplayPlaybackFrame.TimedPlayerState(0, DIMENSION, serverStart)),
                Map.of(playerId, new ReplayPlaybackFrame.TimedPlayerState(100, DIMENSION, serverEnd)),
                Map.of(playerId, new ReplayPlaybackFrame.TimedCameraSample(0, DIMENSION, clientStart)),
                Map.of(playerId, new ReplayPlaybackFrame.TimedCameraSample(100, DIMENSION, clientEnd))
        );

        DecodedPayload.PlayerState interpolated = ReplayMotionInterpolator.playerAt(frame, playerId, serverStart);

        assertEquals(50.0, interpolated.transform().x(), 1.0E-6);
        assertEquals(0.75F, interpolated.animation().orElseThrow().attackProgress(), 1.0E-6F);
        assertEquals(65.62, interpolated.eyeY(), 1.0E-6);
    }

    @Test
    void staleClientVisualTrackFallsBackToServerInterpolation() {
        UUID playerId = UUID.randomUUID();
        DecodedPayload.PlayerState serverStart = player(playerId, 0.0, 0.0F, Optional.empty());
        DecodedPayload.PlayerState serverEnd = player(playerId, 10.0, 0.0F, Optional.empty());
        ReplayPlaybackFrame frame = frame(
                600_000_000L,
                Map.of(playerId, new ReplayPlaybackFrame.TimedPlayerState(500_000_000L, DIMENSION, serverStart)),
                Map.of(playerId, new ReplayPlaybackFrame.TimedPlayerState(700_000_000L, DIMENSION, serverEnd)),
                Map.of(playerId, new ReplayPlaybackFrame.TimedCameraSample(
                        0,
                        DIMENSION,
                        camera(playerId, 100.0, animation(1.0F))
                )),
                Map.of()
        );

        DecodedPayload.PlayerState interpolated = ReplayMotionInterpolator.playerAt(frame, playerId, serverStart);

        assertEquals(5.0, interpolated.transform().x(), 1.0E-6);
    }

    @Test
    void dedicatedPlayerVisualTrackWorksWithoutCameraCapture() {
        UUID playerId = UUID.randomUUID();
        DecodedPayload.PlayerState serverStart = player(playerId, 0.0, 0.0F, Optional.empty());
        DecodedPayload.PlayerState serverEnd = player(playerId, 10.0, 0.0F, Optional.empty());
        DecodedPayload.PlayerVisualSample visualStart = visual(playerId, 20.0, animation(0.25F));
        DecodedPayload.PlayerVisualSample visualEnd = visual(playerId, 40.0, animation(0.75F));
        ReplayWorldSnapshot snapshot = new ReplayWorldSnapshot(
                50,
                0,
                true,
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        ReplayPlaybackFrame frame = new ReplayPlaybackFrame(
                snapshot,
                Map.of(playerId, new ReplayPlaybackFrame.TimedPlayerState(0, DIMENSION, serverStart)),
                Map.of(playerId, new ReplayPlaybackFrame.TimedPlayerState(100, DIMENSION, serverEnd)),
                Map.of(),
                Map.of(),
                Map.of(playerId, new ReplayPlaybackFrame.TimedPlayerVisualSample(0, DIMENSION, visualStart)),
                Map.of(playerId, new ReplayPlaybackFrame.TimedPlayerVisualSample(100, DIMENSION, visualEnd)),
                false
        );

        DecodedPayload.PlayerState interpolated = ReplayMotionInterpolator.playerAt(frame, playerId, serverStart);

        assertEquals(30.0, interpolated.transform().x(), 1.0E-6);
        assertEquals(0.5F, interpolated.animation().orElseThrow().attackProgress(), 1.0E-6F);
    }

    private static ReplayPlaybackFrame frame(
            long target,
            Map<UUID, ReplayPlaybackFrame.TimedPlayerState> currentPlayers,
            Map<UUID, ReplayPlaybackFrame.TimedPlayerState> nextPlayers,
            Map<UUID, ReplayPlaybackFrame.TimedCameraSample> currentCameras,
            Map<UUID, ReplayPlaybackFrame.TimedCameraSample> nextCameras
    ) {
        ReplayWorldSnapshot snapshot = new ReplayWorldSnapshot(
                target,
                0,
                true,
                Map.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );
        return new ReplayPlaybackFrame(
                snapshot,
                currentPlayers,
                nextPlayers,
                currentCameras,
                nextCameras
        );
    }

    private static DecodedPayload.PlayerState player(
            UUID playerId,
            double x,
            float yaw,
            Optional<DecodedPayload.PlayerAnimation> animation
    ) {
        DecodedPayload.Transform transform = transform(x, yaw);
        return new DecodedPayload.PlayerState(
                playerId,
                "ReplayPlayer",
                transform,
                x,
                65.62,
                0.0,
                yaw,
                yaw,
                20.0F,
                0.0F,
                20,
                0,
                "survival",
                List.of(),
                animation
        );
    }

    private static DecodedPayload.CameraSample camera(
            UUID playerId,
            double playerX,
            DecodedPayload.PlayerAnimation animation
    ) {
        return new DecodedPayload.CameraSample(
                playerId,
                0,
                playerX,
                65.62,
                0.0,
                0.0F,
                0.0F,
                0.0F,
                70.0F,
                Optional.of(new DecodedPayload.ClientPlayerSample(
                        transform(playerX, 0.0F),
                        0.0F,
                        0.0F,
                        animation
                ))
        );
    }

    private static DecodedPayload.PlayerVisualSample visual(
            UUID playerId,
            double playerX,
            DecodedPayload.PlayerAnimation animation
    ) {
        return new DecodedPayload.PlayerVisualSample(
                playerId,
                0,
                new DecodedPayload.ClientPlayerSample(
                        transform(playerX, 0.0F),
                        0.0F,
                        0.0F,
                        animation
                )
        );
    }

    private static DecodedPayload.Transform transform(double x, float yaw) {
        return new DecodedPayload.Transform(
                x,
                64.0,
                0.0,
                yaw,
                0.0F,
                0.0,
                0.0,
                0.0,
                "standing",
                true
        );
    }

    private static DecodedPayload.PlayerAnimation animation(float attackProgress) {
        return new DecodedPayload.PlayerAnimation(
                12.0F,
                0.5F,
                attackProgress,
                true,
                3,
                "MAIN_HAND",
                false,
                "MAIN_HAND",
                0,
                0.0F,
                0
        );
    }
}
