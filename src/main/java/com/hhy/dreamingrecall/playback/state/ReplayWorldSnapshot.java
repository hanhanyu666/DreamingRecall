package com.hhy.dreamingrecall.playback.state;

import com.hhy.dreamingrecall.playback.decode.DecodedPayload;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record ReplayWorldSnapshot(
        long archiveNanos,
        long serverTick,
        boolean baselineComplete,
        Map<String, DimensionSnapshot> dimensions,
        List<ChatEntry> recentChat,
        List<SoundEntry> recentSounds,
        List<EntityEffectEntry> recentEntityEffects,
        List<Gap> gaps,
        List<ReplayDiagnostic> diagnostics
) {
    public ReplayWorldSnapshot(
            long archiveNanos,
            long serverTick,
            boolean baselineComplete,
            Map<String, DimensionSnapshot> dimensions,
            List<ChatEntry> recentChat,
            List<SoundEntry> recentSounds,
            List<Gap> gaps,
            List<ReplayDiagnostic> diagnostics
    ) {
        this(
                archiveNanos,
                serverTick,
                baselineComplete,
                dimensions,
                recentChat,
                recentSounds,
                List.of(),
                gaps,
                diagnostics
        );
    }

    public ReplayWorldSnapshot(
            long archiveNanos,
            long serverTick,
            boolean baselineComplete,
            Map<String, DimensionSnapshot> dimensions,
            List<ChatEntry> recentChat,
            List<Gap> gaps,
            List<ReplayDiagnostic> diagnostics
    ) {
        this(
                archiveNanos,
                serverTick,
                baselineComplete,
                dimensions,
                recentChat,
                List.of(),
                List.of(),
                gaps,
                diagnostics
        );
    }

    public ReplayWorldSnapshot {
        if (archiveNanos < 0) {
            throw new IllegalArgumentException("archiveNanos must be non-negative");
        }
        dimensions = Map.copyOf(dimensions);
        recentChat = List.copyOf(recentChat);
        recentSounds = List.copyOf(recentSounds);
        recentEntityEffects = List.copyOf(recentEntityEffects);
        gaps = List.copyOf(gaps);
        diagnostics = List.copyOf(diagnostics);
    }

    public record DimensionSnapshot(
            Optional<DecodedPayload.DimensionState> environment,
            Map<ChunkKey, ChunkSnapshot> chunks,
            Map<UUID, DecodedPayload.EntityState> entities,
            Map<UUID, DecodedPayload.PlayerState> players,
            Map<UUID, DecodedPayload.CameraSample> cameraSamples,
            Map<UUID, DecodedPayload.PlayerVisualSample> playerVisualSamples
    ) {
        public DimensionSnapshot(
                Optional<DecodedPayload.DimensionState> environment,
                Map<ChunkKey, ChunkSnapshot> chunks,
                Map<UUID, DecodedPayload.EntityState> entities,
                Map<UUID, DecodedPayload.PlayerState> players
        ) {
            this(environment, chunks, entities, players, Map.of(), Map.of());
        }

        public DimensionSnapshot(
                Optional<DecodedPayload.DimensionState> environment,
                Map<ChunkKey, ChunkSnapshot> chunks,
                Map<UUID, DecodedPayload.EntityState> entities,
                Map<UUID, DecodedPayload.PlayerState> players,
                Map<UUID, DecodedPayload.CameraSample> cameraSamples
        ) {
            this(environment, chunks, entities, players, cameraSamples, Map.of());
        }

        public DimensionSnapshot {
            Objects.requireNonNull(environment, "environment");
            chunks = Map.copyOf(chunks);
            entities = Map.copyOf(entities);
            players = Map.copyOf(players);
            cameraSamples = Map.copyOf(cameraSamples);
            playerVisualSamples = Map.copyOf(playerVisualSamples);
        }
    }

    public record ChunkKey(int x, int z) {
    }

    public record ChunkSnapshot(
            Optional<DecodedPayload.ChunkBaseline> baseline,
            boolean observed,
            Map<Long, DecodedPayload.BlockState> blockOverrides,
            Map<Long, DecodedPayload.BlockEntityState> blockEntities,
            Map<Integer, DecodedPayload.SectionLight> lightOverrides
    ) {
        public ChunkSnapshot {
            Objects.requireNonNull(baseline, "baseline");
            blockOverrides = Map.copyOf(blockOverrides);
            blockEntities = Map.copyOf(blockEntities);
            lightOverrides = Map.copyOf(lightOverrides);
        }
    }

    public record ChatEntry(
            long archiveNanos,
            long serverTick,
            String dimensionId,
            DecodedPayload.ChatDelivery delivery
    ) {
        public ChatEntry {
            Objects.requireNonNull(dimensionId, "dimensionId");
            Objects.requireNonNull(delivery, "delivery");
        }
    }

    public record SoundEntry(
            long archiveNanos,
            long serverTick,
            String dimensionId,
            DecodedPayload.GameSound sound
    ) {
        public SoundEntry {
            Objects.requireNonNull(dimensionId, "dimensionId");
            Objects.requireNonNull(sound, "sound");
        }
    }

    public record EntityEffectEntry(
            long archiveNanos,
            long serverTick,
            String dimensionId,
            DecodedPayload.EntityEffect effect
    ) {
        public EntityEffectEntry {
            Objects.requireNonNull(dimensionId, "dimensionId");
            Objects.requireNonNull(effect, "effect");
        }
    }

    public record Gap(
            long recordArchiveNanos,
            long startArchiveNanos,
            long endArchiveNanos,
            long droppedRecords
    ) {
    }
}
