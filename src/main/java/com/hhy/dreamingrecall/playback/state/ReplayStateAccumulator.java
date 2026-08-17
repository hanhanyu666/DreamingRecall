package com.hhy.dreamingrecall.playback.state;

import com.hhy.dreamingrecall.archive.ReplayRecord;
import com.hhy.dreamingrecall.playback.decode.DecodedPayload;
import com.hhy.dreamingrecall.playback.decode.PortableRecordDecoder;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ReplayStateAccumulator {
    private static final int MAX_RECENT_CHAT = 200;
    private static final int MAX_RECENT_SOUNDS = 256;
    private static final int MAX_RECENT_ENTITY_EFFECTS = 512;
    private static final int MAX_DIAGNOSTICS = 1024;

    private final PortableRecordDecoder decoder;
    private final Map<String, MutableDimension> dimensions = new LinkedHashMap<>();
    private final Map<UUID, String> playerDimensions = new HashMap<>();
    private final Map<UUID, String> entityDimensions = new HashMap<>();
    private final Map<UUID, String> cameraDimensions = new HashMap<>();
    private final Map<UUID, String> playerVisualDimensions = new HashMap<>();
    private final ArrayDeque<ReplayWorldSnapshot.ChatEntry> recentChat = new ArrayDeque<>();
    private final ArrayDeque<ReplayWorldSnapshot.SoundEntry> recentSounds = new ArrayDeque<>();
    private final ArrayDeque<ReplayWorldSnapshot.EntityEffectEntry> recentEntityEffects = new ArrayDeque<>();
    private final ArrayList<ReplayWorldSnapshot.Gap> gaps = new ArrayList<>();
    private final ArrayList<ReplayDiagnostic> diagnostics = new ArrayList<>();
    private final Set<Integer> unknownTypes = new HashSet<>();

    private long archiveNanos;
    private long serverTick;
    private boolean baselineComplete;

    public ReplayStateAccumulator() {
        this(new PortableRecordDecoder());
    }

    public ReplayStateAccumulator(PortableRecordDecoder decoder) {
        this.decoder = decoder;
    }

    public ReplayStateAccumulator(ReplayWorldSnapshot snapshot) {
        this();
        restore(snapshot);
    }

    public void apply(ReplayRecord record) {
        archiveNanos = Math.max(archiveNanos, record.archiveNanos());
        serverTick = record.serverTick();
        DecodedPayload payload;
        try {
            payload = decoder.decode(record);
        } catch (IOException | RuntimeException failure) {
            addDiagnostic(new ReplayDiagnostic(
                    ReplayDiagnostic.Severity.ERROR,
                    record.archiveNanos(),
                    record.typeId(),
                    record.dimensionId(),
                    failure.getMessage() == null ? failure.getClass().getName() : failure.getMessage()
            ));
            return;
        }

        if (payload instanceof DecodedPayload.BaselineMarker marker) {
            baselineComplete = !marker.begin();
            if (marker.begin() && !dimensions.isEmpty()) {
                dimensions.values().forEach(MutableDimension::beginReplacementBaseline);
                playerDimensions.clear();
                entityDimensions.clear();
                cameraDimensions.clear();
                playerVisualDimensions.clear();
            }
        } else if (payload instanceof DecodedPayload.RecordingGap gap) {
            baselineComplete = false;
            dimensions.values().forEach(MutableDimension::beginReplacementBaseline);
            playerDimensions.clear();
            entityDimensions.clear();
            cameraDimensions.clear();
            playerVisualDimensions.clear();
            gaps.add(new ReplayWorldSnapshot.Gap(
                    record.archiveNanos(),
                    gap.startArchiveNanos(),
                    gap.endArchiveNanos(),
                    gap.droppedRecords()
            ));
        } else if (payload instanceof DecodedPayload.DimensionState state) {
            dimension(record.dimensionId()).environment = state;
        } else if (payload instanceof DecodedPayload.ChunkBaseline chunk) {
            applyChunkBaseline(record.dimensionId(), chunk);
        } else if (payload instanceof DecodedPayload.ChunkCoordinates coordinates) {
            dimension(record.dimensionId())
                    .chunks
                    .computeIfAbsent(
                            new ReplayWorldSnapshot.ChunkKey(coordinates.chunkX(), coordinates.chunkZ()),
                            ignored -> new MutableChunk()
                    )
                    .observed = false;
        } else if (payload instanceof DecodedPayload.BlockChange change) {
            mutableChunkForBlock(record.dimensionId(), change.packedPosition())
                    .blockOverrides
                    .put(change.packedPosition(), change.state());
        } else if (payload instanceof DecodedPayload.BlockEntityUpdate update) {
            DecodedPayload.BlockEntityState blockEntity = update.blockEntity();
            mutableChunkForBlock(record.dimensionId(), blockEntity.packedPosition())
                    .blockEntities
                    .put(blockEntity.packedPosition(), blockEntity);
        } else if (payload instanceof DecodedPayload.BlockEntityRemove remove) {
            mutableChunkForBlock(record.dimensionId(), remove.packedPosition())
                    .blockEntities
                    .remove(remove.packedPosition());
        } else if (payload instanceof DecodedPayload.ChunkLight light) {
            MutableChunk chunk = dimension(record.dimensionId())
                    .chunks
                    .computeIfAbsent(
                            new ReplayWorldSnapshot.ChunkKey(light.chunkX(), light.chunkZ()),
                            ignored -> new MutableChunk()
                    );
            for (DecodedPayload.SectionLight section : light.sections()) {
                chunk.lightOverrides.put(section.sectionY(), section);
            }
        } else if (payload instanceof DecodedPayload.EntityState entity) {
            moveEntity(record.dimensionId(), entity);
        } else if (payload instanceof DecodedPayload.EntityRemove remove) {
            String previousDimension = entityDimensions.remove(remove.uuid());
            if (previousDimension != null) {
                dimension(previousDimension).entities.remove(remove.uuid());
            } else {
                dimensions.values().forEach(value -> value.entities.remove(remove.uuid()));
            }
            String previousPlayerDimension = playerDimensions.remove(remove.uuid());
            if (previousPlayerDimension != null) {
                dimension(previousPlayerDimension).players.remove(remove.uuid());
            } else {
                dimensions.values().forEach(value -> value.players.remove(remove.uuid()));
            }
            String previousCameraDimension = cameraDimensions.remove(remove.uuid());
            if (previousCameraDimension != null) {
                dimension(previousCameraDimension).cameraSamples.remove(remove.uuid());
            } else {
                dimensions.values().forEach(value -> value.cameraSamples.remove(remove.uuid()));
            }
            String previousVisualDimension = playerVisualDimensions.remove(remove.uuid());
            if (previousVisualDimension != null) {
                dimension(previousVisualDimension).playerVisualSamples.remove(remove.uuid());
            } else {
                dimensions.values().forEach(value -> value.playerVisualSamples.remove(remove.uuid()));
            }
        } else if (payload instanceof DecodedPayload.PlayerState player) {
            movePlayer(record.dimensionId(), player);
        } else if (payload instanceof DecodedPayload.CameraSample camera) {
            moveCamera(record.dimensionId(), camera);
        } else if (payload instanceof DecodedPayload.PlayerVisualSample visual) {
            movePlayerVisual(record.dimensionId(), visual);
        } else if (payload instanceof DecodedPayload.ChatDelivery delivery) {
            recentChat.addLast(new ReplayWorldSnapshot.ChatEntry(
                    record.archiveNanos(),
                    record.serverTick(),
                    record.dimensionId(),
                    delivery
            ));
            while (recentChat.size() > MAX_RECENT_CHAT) {
                recentChat.removeFirst();
            }
        } else if (payload instanceof DecodedPayload.GameSound sound) {
            recentSounds.addLast(new ReplayWorldSnapshot.SoundEntry(
                    record.archiveNanos(),
                    record.serverTick(),
                    record.dimensionId(),
                    sound
            ));
            while (recentSounds.size() > MAX_RECENT_SOUNDS) {
                recentSounds.removeFirst();
            }
        } else if (payload instanceof DecodedPayload.EntityEffect effect) {
            recentEntityEffects.addLast(new ReplayWorldSnapshot.EntityEffectEntry(
                    record.archiveNanos(),
                    record.serverTick(),
                    record.dimensionId(),
                    effect
            ));
            while (recentEntityEffects.size() > MAX_RECENT_ENTITY_EFFECTS) {
                recentEntityEffects.removeFirst();
            }
        } else if (payload instanceof DecodedPayload.Unknown unknown && unknownTypes.add(unknown.typeId())) {
            addDiagnostic(new ReplayDiagnostic(
                    ReplayDiagnostic.Severity.INFO,
                    record.archiveNanos(),
                    record.typeId(),
                    record.dimensionId(),
                    "Skipped unknown replay record type " + unknown.typeId()
            ));
        }
    }

    public ReplayWorldSnapshot snapshot() {
        return snapshotAt(archiveNanos);
    }

    public boolean hasWorldState() {
        return !dimensions.isEmpty();
    }

    public ReplayWorldSnapshot snapshotAt(long requestedArchiveNanos) {
        if (requestedArchiveNanos < 0) {
            throw new IllegalArgumentException("requestedArchiveNanos must be non-negative");
        }
        LinkedHashMap<String, ReplayWorldSnapshot.DimensionSnapshot> copiedDimensions = new LinkedHashMap<>();
        dimensions.forEach((id, dimension) -> copiedDimensions.put(id, dimension.snapshot()));
        return new ReplayWorldSnapshot(
                requestedArchiveNanos,
                serverTick,
                baselineComplete,
                copiedDimensions,
                List.copyOf(recentChat),
                List.copyOf(recentSounds),
                List.copyOf(recentEntityEffects),
                List.copyOf(gaps),
                List.copyOf(diagnostics)
        );
    }

    private void restore(ReplayWorldSnapshot snapshot) {
        archiveNanos = snapshot.archiveNanos();
        serverTick = snapshot.serverTick();
        baselineComplete = snapshot.baselineComplete();
        snapshot.dimensions().forEach((id, value) -> {
            MutableDimension dimension = new MutableDimension(value);
            dimensions.put(id, dimension);
            value.players().keySet().forEach(uuid -> playerDimensions.put(uuid, id));
            value.entities().keySet().forEach(uuid -> entityDimensions.put(uuid, id));
            value.cameraSamples().keySet().forEach(uuid -> cameraDimensions.put(uuid, id));
            value.playerVisualSamples().keySet().forEach(uuid -> playerVisualDimensions.put(uuid, id));
        });
        recentChat.addAll(snapshot.recentChat());
        recentSounds.addAll(snapshot.recentSounds());
        recentEntityEffects.addAll(snapshot.recentEntityEffects());
        gaps.addAll(snapshot.gaps());
        diagnostics.addAll(snapshot.diagnostics());
    }

    private void applyChunkBaseline(String dimensionId, DecodedPayload.ChunkBaseline baseline) {
        MutableChunk chunk = new MutableChunk();
        chunk.baseline = baseline;
        chunk.observed = true;
        for (DecodedPayload.BlockEntityState blockEntity : baseline.blockEntities()) {
            chunk.blockEntities.put(blockEntity.packedPosition(), blockEntity);
        }
        dimension(dimensionId).chunks.put(
                new ReplayWorldSnapshot.ChunkKey(baseline.chunkX(), baseline.chunkZ()),
                chunk
        );
    }

    private MutableChunk mutableChunkForBlock(String dimensionId, long packedPosition) {
        int blockX = (int) (packedPosition >> 38);
        int blockZ = (int) (packedPosition << 26 >> 38);
        ReplayWorldSnapshot.ChunkKey key = new ReplayWorldSnapshot.ChunkKey(
                Math.floorDiv(blockX, 16),
                Math.floorDiv(blockZ, 16)
        );
        return dimension(dimensionId).chunks.computeIfAbsent(key, ignored -> new MutableChunk());
    }

    private void moveEntity(String dimensionId, DecodedPayload.EntityState entity) {
        String previous = entityDimensions.put(entity.uuid(), dimensionId);
        if (previous != null && !previous.equals(dimensionId)) {
            dimension(previous).entities.remove(entity.uuid());
        }
        dimension(dimensionId).entities.put(entity.uuid(), entity);
    }

    private void movePlayer(String dimensionId, DecodedPayload.PlayerState player) {
        String previous = playerDimensions.put(player.uuid(), dimensionId);
        if (previous != null && !previous.equals(dimensionId)) {
            dimension(previous).players.remove(player.uuid());
        }
        dimension(dimensionId).players.put(player.uuid(), player);
    }

    private void moveCamera(String dimensionId, DecodedPayload.CameraSample camera) {
        String previous = cameraDimensions.put(camera.playerId(), dimensionId);
        if (previous != null && !previous.equals(dimensionId)) {
            dimension(previous).cameraSamples.remove(camera.playerId());
        }
        dimension(dimensionId).cameraSamples.put(camera.playerId(), camera);
    }

    private void movePlayerVisual(String dimensionId, DecodedPayload.PlayerVisualSample visual) {
        String previous = playerVisualDimensions.put(visual.playerId(), dimensionId);
        if (previous != null && !previous.equals(dimensionId)) {
            dimension(previous).playerVisualSamples.remove(visual.playerId());
        }
        dimension(dimensionId).playerVisualSamples.put(visual.playerId(), visual);
    }

    private MutableDimension dimension(String dimensionId) {
        return dimensions.computeIfAbsent(dimensionId, ignored -> new MutableDimension());
    }

    private void addDiagnostic(ReplayDiagnostic diagnostic) {
        if (diagnostics.size() < MAX_DIAGNOSTICS) {
            diagnostics.add(diagnostic);
        }
    }

    private static final class MutableDimension {
        private DecodedPayload.DimensionState environment;
        private final Map<ReplayWorldSnapshot.ChunkKey, MutableChunk> chunks = new LinkedHashMap<>();
        private final Map<UUID, DecodedPayload.EntityState> entities = new LinkedHashMap<>();
        private final Map<UUID, DecodedPayload.PlayerState> players = new LinkedHashMap<>();
        private final Map<UUID, DecodedPayload.CameraSample> cameraSamples = new LinkedHashMap<>();
        private final Map<UUID, DecodedPayload.PlayerVisualSample> playerVisualSamples = new LinkedHashMap<>();

        private MutableDimension() {
        }

        private MutableDimension(ReplayWorldSnapshot.DimensionSnapshot snapshot) {
            environment = snapshot.environment().orElse(null);
            snapshot.chunks().forEach((key, value) -> chunks.put(key, new MutableChunk(value)));
            entities.putAll(snapshot.entities());
            players.putAll(snapshot.players());
            cameraSamples.putAll(snapshot.cameraSamples());
            playerVisualSamples.putAll(snapshot.playerVisualSamples());
        }

        private ReplayWorldSnapshot.DimensionSnapshot snapshot() {
            LinkedHashMap<ReplayWorldSnapshot.ChunkKey, ReplayWorldSnapshot.ChunkSnapshot> copiedChunks =
                    new LinkedHashMap<>();
            chunks.forEach((key, chunk) -> copiedChunks.put(key, chunk.snapshot()));
            return new ReplayWorldSnapshot.DimensionSnapshot(
                    Optional.ofNullable(environment),
                    copiedChunks,
                    entities,
                    players,
                    cameraSamples,
                    playerVisualSamples
            );
        }

        private void beginReplacementBaseline() {
            chunks.values().forEach(chunk -> chunk.observed = false);
            entities.clear();
            players.clear();
            cameraSamples.clear();
            playerVisualSamples.clear();
        }
    }

    private static final class MutableChunk {
        private DecodedPayload.ChunkBaseline baseline;
        private boolean observed;
        private final Map<Long, DecodedPayload.BlockState> blockOverrides = new LinkedHashMap<>();
        private final Map<Long, DecodedPayload.BlockEntityState> blockEntities = new LinkedHashMap<>();
        private final Map<Integer, DecodedPayload.SectionLight> lightOverrides = new LinkedHashMap<>();

        private MutableChunk() {
        }

        private MutableChunk(ReplayWorldSnapshot.ChunkSnapshot snapshot) {
            baseline = snapshot.baseline().orElse(null);
            observed = snapshot.observed();
            blockOverrides.putAll(snapshot.blockOverrides());
            blockEntities.putAll(snapshot.blockEntities());
            lightOverrides.putAll(snapshot.lightOverrides());
        }

        private ReplayWorldSnapshot.ChunkSnapshot snapshot() {
            return new ReplayWorldSnapshot.ChunkSnapshot(
                    Optional.ofNullable(baseline),
                    observed,
                    blockOverrides,
                    blockEntities,
                    lightOverrides
            );
        }
    }
}
