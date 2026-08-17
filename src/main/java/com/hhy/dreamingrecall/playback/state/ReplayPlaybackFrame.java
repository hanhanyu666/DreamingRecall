package com.hhy.dreamingrecall.playback.state;

import com.hhy.dreamingrecall.playback.decode.DecodedPayload;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ReplayPlaybackFrame(
        ReplayWorldSnapshot snapshot,
        Map<UUID, TimedPlayerState> currentPlayers,
        Map<UUID, TimedPlayerState> nextPlayers,
        Map<UUID, TimedCameraSample> currentCameraSamples,
        Map<UUID, TimedCameraSample> nextCameraSamples,
        Map<UUID, TimedPlayerVisualSample> currentPlayerVisualSamples,
        Map<UUID, TimedPlayerVisualSample> nextPlayerVisualSamples,
        boolean worldStateChanged
) {
    public ReplayPlaybackFrame(
            ReplayWorldSnapshot snapshot,
            Map<UUID, TimedPlayerState> currentPlayers,
            Map<UUID, TimedPlayerState> nextPlayers,
            Map<UUID, TimedCameraSample> currentCameraSamples,
            Map<UUID, TimedCameraSample> nextCameraSamples,
            boolean worldStateChanged
    ) {
        this(
                snapshot,
                currentPlayers,
                nextPlayers,
                currentCameraSamples,
                nextCameraSamples,
                Map.of(),
                Map.of(),
                worldStateChanged
        );
    }

    public ReplayPlaybackFrame(
            ReplayWorldSnapshot snapshot,
            Map<UUID, TimedPlayerState> currentPlayers,
            Map<UUID, TimedPlayerState> nextPlayers,
            Map<UUID, TimedCameraSample> currentCameraSamples,
            Map<UUID, TimedCameraSample> nextCameraSamples
    ) {
        this(
                snapshot,
                currentPlayers,
                nextPlayers,
                currentCameraSamples,
                nextCameraSamples,
                Map.of(),
                Map.of(),
                true
        );
    }

    public ReplayPlaybackFrame {
        Objects.requireNonNull(snapshot, "snapshot");
        currentPlayers = Map.copyOf(currentPlayers);
        nextPlayers = Map.copyOf(nextPlayers);
        currentCameraSamples = Map.copyOf(currentCameraSamples);
        nextCameraSamples = Map.copyOf(nextCameraSamples);
        currentPlayerVisualSamples = Map.copyOf(currentPlayerVisualSamples);
        nextPlayerVisualSamples = Map.copyOf(nextPlayerVisualSamples);
    }

    public record TimedPlayerState(
            long archiveNanos,
            String dimensionId,
            DecodedPayload.PlayerState state
    ) {
        public TimedPlayerState {
            Objects.requireNonNull(dimensionId, "dimensionId");
            Objects.requireNonNull(state, "state");
        }
    }

    public record TimedCameraSample(
            long archiveNanos,
            String dimensionId,
            DecodedPayload.CameraSample sample
    ) {
        public TimedCameraSample {
            Objects.requireNonNull(dimensionId, "dimensionId");
            Objects.requireNonNull(sample, "sample");
        }
    }

    public record TimedPlayerVisualSample(
            long archiveNanos,
            String dimensionId,
            DecodedPayload.PlayerVisualSample sample
    ) {
        public TimedPlayerVisualSample {
            Objects.requireNonNull(dimensionId, "dimensionId");
            Objects.requireNonNull(sample, "sample");
        }
    }
}
