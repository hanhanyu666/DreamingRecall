package com.hhy.dreamingrecall.recording;

import com.hhy.dreamingrecall.archive.CoreRecordType;
import com.hhy.dreamingrecall.archive.ReplayRecord;
import com.hhy.dreamingrecall.capture.BinaryPayloads;
import com.hhy.dreamingrecall.playback.decode.DecodedPayload;
import com.hhy.dreamingrecall.playback.decode.PortableRecordDecoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ReplayCheckpointBuilder {
    private static final int MAX_RECENT_CHAT = 200;
    private static final int MAX_GAPS = 1024;

    private final PortableRecordDecoder decoder = new PortableRecordDecoder();
    private final Map<String, ReplayRecord> dimensions = new LinkedHashMap<>();
    private final Map<ChunkKey, ChunkRecords> chunks = new LinkedHashMap<>();
    private final Map<UUID, ReplayRecord> entities = new LinkedHashMap<>();
    private final Map<UUID, ReplayRecord> players = new LinkedHashMap<>();
    private final Map<UUID, ReplayRecord> cameras = new LinkedHashMap<>();
    private final Map<UUID, ReplayRecord> playerVisuals = new LinkedHashMap<>();
    private final ArrayDeque<ReplayRecord> recentChat = new ArrayDeque<>();
    private final ArrayDeque<ReplayRecord> gaps = new ArrayDeque<>();

    private boolean baselineComplete;

    void accept(ReplayRecord original, ReplayRecord archived) throws IOException {
        DecodedPayload payload = decoder.decode(original);
        if (payload instanceof DecodedPayload.BaselineMarker marker) {
            baselineComplete = !marker.begin();
            if (marker.begin() && !dimensions.isEmpty()) {
                beginReplacementBaseline();
            }
        } else if (payload instanceof DecodedPayload.RecordingGap) {
            baselineComplete = false;
            beginReplacementBaseline();
            addBounded(gaps, archived, MAX_GAPS);
        } else if (payload instanceof DecodedPayload.DimensionState) {
            dimensions.put(original.dimensionId(), archived);
        } else if (payload instanceof DecodedPayload.ChunkBaseline baseline) {
            chunks.put(
                    new ChunkKey(original.dimensionId(), baseline.chunkX(), baseline.chunkZ()),
                    new ChunkRecords(archived)
            );
        } else if (payload instanceof DecodedPayload.ChunkCoordinates coordinates) {
            chunk(original.dimensionId(), coordinates.chunkX(), coordinates.chunkZ()).observed = false;
        } else if (payload instanceof DecodedPayload.BlockChange change) {
            chunkForBlock(original.dimensionId(), change.packedPosition())
                    .blockChanges.put(change.packedPosition(), archived);
        } else if (payload instanceof DecodedPayload.BlockEntityUpdate update) {
            long position = update.blockEntity().packedPosition();
            chunkForBlock(original.dimensionId(), position).blockEntityChanges.put(position, archived);
        } else if (payload instanceof DecodedPayload.BlockEntityRemove remove) {
            chunkForBlock(original.dimensionId(), remove.packedPosition())
                    .blockEntityChanges.put(remove.packedPosition(), archived);
        } else if (payload instanceof DecodedPayload.ChunkLight light) {
            chunk(original.dimensionId(), light.chunkX(), light.chunkZ()).light = archived;
        } else if (payload instanceof DecodedPayload.EntityState entity) {
            entities.put(entity.uuid(), archived);
        } else if (payload instanceof DecodedPayload.EntityRemove remove) {
            entities.remove(remove.uuid());
            players.remove(remove.uuid());
            cameras.remove(remove.uuid());
            playerVisuals.remove(remove.uuid());
        } else if (payload instanceof DecodedPayload.PlayerState player) {
            players.put(player.uuid(), archived);
        } else if (payload instanceof DecodedPayload.CameraSample camera) {
            cameras.put(camera.playerId(), archived);
        } else if (payload instanceof DecodedPayload.PlayerVisualSample visual) {
            playerVisuals.put(visual.playerId(), archived);
        } else if (payload instanceof DecodedPayload.ChatDelivery) {
            addBounded(recentChat, archived, MAX_RECENT_CHAT);
        }
    }

    boolean canCheckpoint() {
        return baselineComplete && !dimensions.isEmpty();
    }

    List<ReplayRecord> snapshotRecords(long archiveNanos, long serverTick) {
        ArrayList<ReplayRecord> records = new ArrayList<>();
        records.addAll(gaps);
        records.add(ReplayRecord.control(
                CoreRecordType.BASELINE_BEGIN,
                archiveNanos,
                serverTick,
                "persisted_checkpoint".getBytes(StandardCharsets.UTF_8)
        ));
        records.addAll(dimensions.values());
        chunks.forEach((key, state) -> {
            if (state.baseline != null) {
                records.add(state.baseline);
            }
            records.addAll(state.blockChanges.values());
            records.addAll(state.blockEntityChanges.values());
            if (state.light != null) {
                records.add(state.light);
            }
            if (!state.observed) {
                records.add(ReplayRecord.core(
                        CoreRecordType.CHUNK_OBSERVATION_END,
                        archiveNanos,
                        serverTick,
                        key.dimensionId(),
                        BinaryPayloads.chunkCoordinates(key.x(), key.z())
                ));
            }
        });
        records.addAll(entities.values());
        records.addAll(players.values());
        records.addAll(cameras.values());
        records.addAll(playerVisuals.values());
        records.addAll(recentChat);
        records.add(ReplayRecord.control(CoreRecordType.BASELINE_END, archiveNanos, serverTick, new byte[0]));
        records.add(ReplayRecord.core(CoreRecordType.SERVER_TICK, archiveNanos, serverTick, "", new byte[0]));
        return List.copyOf(records);
    }

    private void beginReplacementBaseline() {
        chunks.values().forEach(state -> state.observed = false);
        entities.clear();
        players.clear();
        cameras.clear();
        playerVisuals.clear();
    }

    private ChunkRecords chunkForBlock(String dimensionId, long packedPosition) {
        int blockX = (int) (packedPosition >> 38);
        int blockZ = (int) (packedPosition << 26 >> 38);
        return chunk(dimensionId, Math.floorDiv(blockX, 16), Math.floorDiv(blockZ, 16));
    }

    private ChunkRecords chunk(String dimensionId, int x, int z) {
        return chunks.computeIfAbsent(new ChunkKey(dimensionId, x, z), ignored -> new ChunkRecords(null));
    }

    private static void addBounded(ArrayDeque<ReplayRecord> records, ReplayRecord record, int maximum) {
        records.addLast(record);
        while (records.size() > maximum) {
            records.removeFirst();
        }
    }

    private record ChunkKey(String dimensionId, int x, int z) {
    }

    private static final class ChunkRecords {
        private final ReplayRecord baseline;
        private final Map<Long, ReplayRecord> blockChanges = new LinkedHashMap<>();
        private final Map<Long, ReplayRecord> blockEntityChanges = new LinkedHashMap<>();
        private ReplayRecord light;
        private boolean observed = true;

        private ChunkRecords(ReplayRecord baseline) {
            this.baseline = baseline;
        }
    }
}
