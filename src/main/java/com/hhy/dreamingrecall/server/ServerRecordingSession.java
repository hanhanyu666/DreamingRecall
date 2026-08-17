package com.hhy.dreamingrecall.server;

import com.hhy.dreamingrecall.DreamingRecall;
import com.hhy.dreamingrecall.api.extension.ExtensionFrame;
import com.hhy.dreamingrecall.api.extension.ExtensionFrameCodec;
import com.hhy.dreamingrecall.api.extension.ExtensionSubmissionResult;
import com.hhy.dreamingrecall.api.extension.ReplayExtension;
import com.hhy.dreamingrecall.archive.ArchiveManifest;
import com.hhy.dreamingrecall.archive.ArchiveAttachmentStore;
import com.hhy.dreamingrecall.archive.CoreRecordType;
import com.hhy.dreamingrecall.archive.ReplayRecord;
import com.hhy.dreamingrecall.archive.RecordPriority;
import com.hhy.dreamingrecall.capture.MinecraftRecordEncoder;
import com.hhy.dreamingrecall.config.DreamingRecallConfig;
import com.hhy.dreamingrecall.mixin.ChunkMapAccessor;
import com.hhy.dreamingrecall.recording.OfferResult;
import com.hhy.dreamingrecall.recording.PipelineState;
import com.hhy.dreamingrecall.recording.RecordingMetricsSnapshot;
import com.hhy.dreamingrecall.recording.RecordingPipeline;
import com.hhy.dreamingrecall.recording.TickCostWindow;
import com.hhy.dreamingrecall.network.CameraSamplePayload;
import com.hhy.dreamingrecall.network.PlayerVisualSamplePayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

final class ServerRecordingSession {
    private static final int MAX_CHUNK_CAPTURE_RETRIES = 200;

    private final MinecraftServer server;
    private final RecordingPipeline pipeline;
    private final long startMonotonicNanos;
    private final long startServerTick;
    private final RecordingMode mode;
    private final ArrayDeque<PendingChunk> pendingChunks = new ArrayDeque<>();
    private final Set<ChunkKey> pendingChunkKeys = new HashSet<>();
    private final Set<ChunkKey> observedChunks = new HashSet<>();
    private final Set<ChunkKey> dirtyLightChunks = new HashSet<>();
    private final Map<BlockEntityKey, BlockEntity> dirtyBlockEntities = new LinkedHashMap<>();
    private final Map<UUID, Long> entityFingerprints = new HashMap<>();
    private final Map<UUID, Entity> trackedEntities = new HashMap<>();
    private final RoundRobinSchedule<UUID> entitySchedule = new RoundRobinSchedule<>();
    private final ConcurrentLinkedQueue<PendingChat> pendingChat = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<PendingEntityEffect> pendingEntityEffects = new ConcurrentLinkedQueue<>();
    private final Map<String, Integer> captureFailures = new HashMap<>();
    private final Map<UUID, Long> lastCameraSampleNanos = new HashMap<>();
    private final Map<UUID, Long> lastPlayerVisualSampleNanos = new HashMap<>();
    private final TickCostWindow tickCosts = new TickCostWindow(2048);

    private boolean stopping;
    private boolean baselineRunning;
    private long baselineGeneration = -1;
    private boolean baselineBeginAccepted;
    private int lastDimensionStateTick = Integer.MIN_VALUE;

    ServerRecordingSession(
            MinecraftServer server,
            Path archiveRoot,
            ArchiveManifest manifest,
            RecordingMode mode
    ) {
        this.server = server;
        this.mode = mode;
        this.startMonotonicNanos = System.nanoTime();
        this.startServerTick = server.getTickCount();
        this.pipeline = new RecordingPipeline(
                archiveRoot,
                manifest,
                DreamingRecallConfig.recordingSettings(),
                failure -> DreamingRecall.LOGGER.error("DreamingRecall archive writer failed", failure)
        );
    }

    void start() {
        pipeline.start();
        beginBaseline("initial", -1);
    }

    void tick() {
        long captureStarted = System.nanoTime();
        try {
            tickInternal();
        } finally {
            tickCosts.record(System.nanoTime() - captureStarted);
        }
    }

    private void tickInternal() {
        if (stopping || pipeline.state() == PipelineState.FAILED) {
            return;
        }
        long archiveNanos = archiveNanos();
        long serverTick = serverTick();

        if (pipeline.requiresBaseline() && (!baselineRunning || pipeline.baselineGeneration() != baselineGeneration)) {
            beginBaseline("recorder_overload_recovery", pipeline.baselineGeneration());
        }

        offerCore(CoreRecordType.SERVER_TICK, "", archiveNanos, serverTick, new byte[0]);
        capturePlayers(archiveNanos, serverTick);
        int skippedNewEntities = captureEntities(archiveNanos, serverTick);
        captureDimensionStates(archiveNanos, serverTick);
        capturePendingChunks(archiveNanos, serverTick);
        captureDirtyBlockEntities(archiveNanos, serverTick);
        captureDirtyLight(archiveNanos, serverTick);
        flushChat(archiveNanos, serverTick);
        flushEntityEffects(archiveNanos, serverTick);

        if (baselineRunning && pendingChunks.isEmpty() && skippedNewEntities == 0) {
            completeBaseline(archiveNanos, serverTick);
        }
    }

    void enqueueChunk(ServerLevel level, int chunkX, int chunkZ) {
        if (stopping) {
            return;
        }
        ChunkKey key = new ChunkKey(level, chunkX, chunkZ);
        if (observedChunks.contains(key) || !pendingChunkKeys.add(key)) {
            return;
        }
        pendingChunks.addLast(new PendingChunk(key, 0));
    }

    void chunkUnloaded(ServerLevel level, int chunkX, int chunkZ) {
        ChunkKey key = new ChunkKey(level, chunkX, chunkZ);
        pendingChunkKeys.remove(key);
        pendingChunks.removeIf(pending -> pending.key().equals(key));
        dirtyLightChunks.remove(key);
        dirtyBlockEntities.keySet().removeIf(blockEntity -> blockEntity.chunk().equals(key));
        if (!observedChunks.remove(key) || stopping) {
            return;
        }
        byte[] payload = com.hhy.dreamingrecall.capture.BinaryPayloads.chunkCoordinates(chunkX, chunkZ);
        offerCore(
                CoreRecordType.CHUNK_OBSERVATION_END,
                dimensionId(level),
                archiveNanos(),
                serverTick(),
                payload
        );
    }

    void blockChanged(LevelChunk chunk, BlockPos pos, BlockState newState) {
        if (stopping || !(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ChunkKey key = new ChunkKey(level, chunk.getPos().x, chunk.getPos().z);
        if (!observedChunks.contains(key)) {
            return;
        }
        try {
            offerCore(
                    CoreRecordType.BLOCK_CHANGE,
                    dimensionId(level),
                    archiveNanos(),
                    serverTick(),
                    MinecraftRecordEncoder.blockChange(pos, newState)
            );
            dirtyLightChunks.add(key);
        } catch (Throwable failure) {
            recordCaptureFailure("block:" + newState.getBlock().getClass().getName(), failure);
        }
    }

    void blockEntityChanged(BlockEntity blockEntity) {
        if (stopping || !(blockEntity.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos pos = blockEntity.getBlockPos();
        ChunkKey chunk = new ChunkKey(level, pos.getX() >> 4, pos.getZ() >> 4);
        if (observedChunks.contains(chunk)) {
            dirtyBlockEntities.put(new BlockEntityKey(chunk, pos.asLong()), blockEntity);
        }
    }

    void blockEntityRemoved(LevelChunk chunk, BlockPos pos) {
        if (stopping || !(chunk.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ChunkKey key = new ChunkKey(level, chunk.getPos().x, chunk.getPos().z);
        dirtyBlockEntities.remove(new BlockEntityKey(key, pos.asLong()));
        if (observedChunks.contains(key)) {
            offerCore(
                    CoreRecordType.BLOCK_ENTITY_REMOVE,
                    key.dimensionId(),
                    archiveNanos(),
                    serverTick(),
                    com.hhy.dreamingrecall.capture.BinaryPayloads.blockPosition(pos.asLong())
            );
        }
    }

    void entityJoined(Entity entity) {
        if (stopping || entity instanceof ServerPlayer || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        trackEntity(entity);
        captureEntity(entity, level, true, archiveNanos(), serverTick());
    }

    void entityLeft(Entity entity) {
        if (stopping || !(entity.level() instanceof ServerLevel level)) {
            return;
        }
        if (entity instanceof ServerPlayer) {
            lastCameraSampleNanos.remove(entity.getUUID());
            offerCore(
                    CoreRecordType.ENTITY_REMOVE,
                    dimensionId(level),
                    archiveNanos(),
                    serverTick(),
                    MinecraftRecordEncoder.entityRemoved(entity)
            );
            return;
        }
        Long previousFingerprint = entityFingerprints.remove(entity.getUUID());
        trackedEntities.remove(entity.getUUID());
        entitySchedule.remove(entity.getUUID());
        if (previousFingerprint == null) {
            return;
        }
        offerCore(
                CoreRecordType.ENTITY_REMOVE,
                dimensionId(level),
                archiveNanos(),
                serverTick(),
                MinecraftRecordEncoder.entityRemoved(entity)
        );
    }

    void chatDelivered(ServerPlayer recipient, Component rendered, String kind, int deliveryToken) {
        if (stopping || !DreamingRecallConfig.CAPTURE_CHAT.get()) {
            return;
        }
        try {
            String json = Component.Serializer.toJson(rendered, recipient.registryAccess());
            pendingChat.add(new PendingChat(deliveryToken, recipient.getUUID(), json, kind));
        } catch (Throwable failure) {
            recordCaptureFailure("chat:" + rendered.getClass().getName(), failure);
        }
    }

    void entityEffect(
            ServerPlayer recipient,
            UUID entityId,
            String effect,
            int deliveryToken
    ) {
        if (stopping) {
            return;
        }
        pendingEntityEffects.add(new PendingEntityEffect(
                deliveryToken,
                dimensionId(recipient.serverLevel()),
                entityId,
                effect
        ));
    }

    void soundPlayed(
            ServerLevel level,
            Holder<SoundEvent> sound,
            SoundSource source,
            Vec3 position,
            float volume,
            float pitch
    ) {
        if (stopping || !DreamingRecallConfig.CAPTURE_SOUNDS.get()) {
            return;
        }
        try {
            offerCore(
                    CoreRecordType.GAME_SOUND,
                    dimensionId(level),
                    archiveNanos(),
                    serverTick(),
                    MinecraftRecordEncoder.sound(sound, source, position, volume, pitch)
            );
        } catch (Throwable failure) {
            recordCaptureFailure("sound", failure);
        }
    }

    void clientCameraSample(ServerPlayer player, CameraSamplePayload sample) {
        if (stopping || !DreamingRecallConfig.CLIENT_CAMERA_TRACKS_ALLOWED.get()) {
            return;
        }
        long now = System.nanoTime();
        long previous = lastCameraSampleNanos.getOrDefault(player.getUUID(), Long.MIN_VALUE);
        if (previous != Long.MIN_VALUE && now - previous < 5_000_000L) {
            return;
        }
        lastCameraSampleNanos.put(player.getUUID(), now);
        byte[] payload = MinecraftRecordEncoder.clientCameraSample(
                player.getUUID(),
                sample
        );
        pipeline.offer(new ReplayRecord(
                CoreRecordType.CLIENT_CAMERA_SAMPLE.id(),
                RecordPriority.ENHANCEMENT,
                archiveNanos(),
                serverTick(),
                dimensionId(player.serverLevel()),
                payload
        ));
    }

    void clientPlayerVisualSample(ServerPlayer player, PlayerVisualSamplePayload sample) {
        if (stopping) {
            return;
        }
        long now = System.nanoTime();
        long previous = lastPlayerVisualSampleNanos.getOrDefault(player.getUUID(), Long.MIN_VALUE);
        if (previous != Long.MIN_VALUE && now - previous < 20_000_000L) {
            return;
        }
        lastPlayerVisualSampleNanos.put(player.getUUID(), now);
        pipeline.offer(new ReplayRecord(
                CoreRecordType.CLIENT_PLAYER_VISUAL_SAMPLE.id(),
                RecordPriority.ENHANCEMENT,
                archiveNanos(),
                serverTick(),
                dimensionId(player.serverLevel()),
                MinecraftRecordEncoder.clientPlayerVisualSample(player.getUUID(), sample)
        ));
    }

    ExtensionSubmissionResult submitExtension(
            ReplayExtension extension,
            String channel,
            String scope,
            byte[] payload
    ) {
        if (stopping) {
            return ExtensionSubmissionResult.NOT_RECORDING;
        }
        try {
            byte[] envelope = ExtensionFrameCodec.encode(new ExtensionFrame(
                    extension.id().toString(),
                    extension.schemaVersion(),
                    channel,
                    scope,
                    payload
            ));
            OfferResult result = pipeline.offer(new ReplayRecord(
                    CoreRecordType.EXTENSION_PAYLOAD.id(),
                    RecordPriority.ENHANCEMENT,
                    archiveNanos(),
                    serverTick(),
                    "",
                    envelope
            ));
            return result == OfferResult.ACCEPTED
                    ? ExtensionSubmissionResult.ACCEPTED
                    : ExtensionSubmissionResult.DROPPED_BY_BACKPRESSURE;
        } catch (Exception failure) {
            recordCaptureFailure("extension:" + extension.id(), failure);
            return ExtensionSubmissionResult.DROPPED_BY_BACKPRESSURE;
        }
    }

    void requestStop() {
        if (stopping) {
            return;
        }
        stopping = true;
        long archiveNanos = archiveNanos();
        long serverTick = serverTick();
        flushChat(archiveNanos, serverTick);
        flushEntityEffects(archiveNanos, serverTick);
        pipeline.requestStop(archiveNanos, serverTick);
    }

    java.util.Optional<ArchiveAttachmentStore.AttachmentReference> attachResourcePack(Path source, long maxBytes)
            throws java.io.IOException {
        return pipeline.attachResourcePack(source, maxBytes);
    }

    boolean awaitStopped(Duration timeout) {
        try {
            pipeline.stoppedFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception failure) {
            DreamingRecall.LOGGER.error("Timed out or failed while closing a DreamingRecall archive", failure);
            return false;
        }
    }

    void whenStopped(Runnable callback) {
        pipeline.stoppedFuture().whenComplete((path, failure) -> callback.run());
    }

    ServerRecordingStatus snapshot() {
        return new ServerRecordingStatus(
                pipeline.state(),
                mode,
                archiveNanos(),
                baselineRunning,
                pendingChunks.size(),
                observedChunks.size(),
                pipeline.metrics(),
                tickCosts.snapshot(),
                pipeline.readyFuture().getNow(null)
        );
    }

    private void beginBaseline(String reason, long generation) {
        baselineRunning = true;
        baselineGeneration = generation;
        baselineBeginAccepted = false;
        pendingChunks.clear();
        pendingChunkKeys.clear();
        observedChunks.clear();
        dirtyLightChunks.clear();
        dirtyBlockEntities.clear();
        entityFingerprints.clear();
        trackedEntities.clear();
        entitySchedule.clear();
        enqueueAllLoadedEntities();
        enqueueAllLoadedChunks();
        baselineBeginAccepted = offerControl(
                CoreRecordType.BASELINE_BEGIN,
                archiveNanos(),
                serverTick(),
                reason.getBytes(StandardCharsets.UTF_8)
        ) == OfferResult.ACCEPTED;
    }

    private void completeBaseline(long archiveNanos, long serverTick) {
        if (!baselineBeginAccepted) {
            baselineBeginAccepted = offerControl(
                    CoreRecordType.BASELINE_BEGIN,
                    archiveNanos,
                    serverTick,
                    "baseline_retry".getBytes(StandardCharsets.UTF_8)
            ) == OfferResult.ACCEPTED;
            return;
        }
        OfferResult result = offerControl(CoreRecordType.BASELINE_END, archiveNanos, serverTick, new byte[0]);
        if (result != OfferResult.ACCEPTED) {
            return;
        }
        if (baselineGeneration >= 0) {
            pipeline.markBaselineComplete(baselineGeneration);
        }
        baselineRunning = false;
    }

    private void enqueueAllLoadedChunks() {
        for (ServerLevel level : server.getAllLevels()) {
            Iterable<ChunkHolder> holders = ((ChunkMapAccessor) level.getChunkSource().chunkMap).dreamingrecall$getChunks();
            for (ChunkHolder holder : holders) {
                LevelChunk chunk = holder.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null);
                if (chunk != null) {
                    enqueueChunk(level, chunk.getPos().x, chunk.getPos().z);
                }
            }
        }
    }

    private void capturePendingChunks(long archiveNanos, long serverTick) {
        int budget = DreamingRecallConfig.BASELINE_CHUNKS_PER_TICK.get();
        for (int index = 0; index < budget && !pendingChunks.isEmpty(); index++) {
            PendingChunk pending = pendingChunks.removeFirst();
            pendingChunkKeys.remove(pending.key());
            LevelChunk chunk = resolveFullChunk(pending.key());
            if (chunk == null) {
                if (pending.attempts() < MAX_CHUNK_CAPTURE_RETRIES) {
                    PendingChunk retry = new PendingChunk(pending.key(), pending.attempts() + 1);
                    pendingChunks.addLast(retry);
                    pendingChunkKeys.add(retry.key());
                }
                continue;
            }
            captureChunkBaseline(pending.key(), chunk, archiveNanos, serverTick);
        }
    }

    private LevelChunk resolveFullChunk(ChunkKey key) {
        ChunkHolder holder = key.level().getChunkSource().chunkMap.getVisibleChunkIfPresent(ChunkPos.asLong(key.x(), key.z()));
        return holder == null
                ? null
                : holder.getFullChunkFuture().getNow(ChunkHolder.UNLOADED_LEVEL_CHUNK).orElse(null);
    }

    private void captureChunkBaseline(ChunkKey key, LevelChunk chunk, long archiveNanos, long serverTick) {
        byte[] payload;
        try {
            payload = MinecraftRecordEncoder.chunkBaseline(key.level(), chunk);
        } catch (Throwable failure) {
            recordCaptureFailure("chunk:" + key.dimensionId(), failure);
            payload = MinecraftRecordEncoder.chunkPlaceholder(chunk, failure.getClass().getName());
        }
        OfferResult result = offerCore(
                CoreRecordType.CHUNK_BASELINE,
                key.dimensionId(),
                archiveNanos,
                serverTick,
                payload
        );
        if (result == OfferResult.REJECTED_TOO_LARGE) {
            result = offerCore(
                    CoreRecordType.CHUNK_BASELINE,
                    key.dimensionId(),
                    archiveNanos,
                    serverTick,
                    MinecraftRecordEncoder.chunkPlaceholder(chunk, "record_too_large")
            );
        }
        if (result == OfferResult.ACCEPTED) {
            observedChunks.add(key);
        } else if (!stopping) {
            enqueueChunk(key.level(), key.x(), key.z());
        }
    }

    private void capturePlayers(long archiveNanos, long serverTick) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                offerCore(
                        CoreRecordType.PLAYER_STATE,
                        dimensionId(player.serverLevel()),
                        archiveNanos,
                        serverTick,
                        MinecraftRecordEncoder.playerState(player)
                );
            } catch (Throwable failure) {
                recordCaptureFailure("player:" + player.getUUID(), failure);
            }
        }
    }

    private int captureEntities(long archiveNanos, long serverTick) {
        int newEntityBudget = DreamingRecallConfig.NEW_ENTITIES_PER_TICK.get();
        int updateBudget = Math.min(DreamingRecallConfig.ENTITY_UPDATES_PER_TICK.get(), entitySchedule.size());
        int skippedNew = 0;
        for (int index = 0; index < updateBudget; index++) {
            UUID uuid = entitySchedule.next();
            if (uuid == null) {
                break;
            }
            Entity entity = trackedEntities.get(uuid);
            if (entity == null || entity.isRemoved() || entity instanceof ServerPlayer
                    || !(entity.level() instanceof ServerLevel level)) {
                trackedEntities.remove(uuid);
                entitySchedule.remove(uuid);
                entityFingerprints.remove(uuid);
                continue;
            }
            Long previous = entityFingerprints.get(uuid);
            if (previous == null) {
                if (newEntityBudget-- > 0) {
                    captureEntity(entity, level, true, archiveNanos, serverTick);
                } else {
                    skippedNew++;
                }
                continue;
            }
            try {
                long fingerprint = MinecraftRecordEncoder.entityFingerprint(entity);
                if (fingerprint != previous) {
                    if (offerCore(
                            CoreRecordType.ENTITY_STATE,
                            dimensionId(level),
                            archiveNanos,
                            serverTick,
                            MinecraftRecordEncoder.entityState(entity, false)
                    ) == OfferResult.ACCEPTED) {
                        entityFingerprints.put(uuid, fingerprint);
                    }
                }
            } catch (Throwable failure) {
                recordCaptureFailure("entity_state:" + entity.getType().getClass().getName(), failure);
            }
        }
        if (entityFingerprints.size() < trackedEntities.size()) {
            skippedNew += trackedEntities.size() - entityFingerprints.size();
        }
        return skippedNew;
    }

    private void enqueueAllLoadedEntities() {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof ServerPlayer)) {
                    trackEntity(entity);
                }
            }
        }
    }

    private void trackEntity(Entity entity) {
        trackedEntities.put(entity.getUUID(), entity);
        entitySchedule.add(entity.getUUID());
    }

    private void captureEntity(Entity entity, ServerLevel level, boolean spawn, long archiveNanos, long serverTick) {
        byte[] payload;
        long fingerprint;
        try {
            payload = MinecraftRecordEncoder.entityState(entity, spawn);
            fingerprint = MinecraftRecordEncoder.entityFingerprint(entity);
        } catch (Throwable failure) {
            recordCaptureFailure("entity_spawn:" + entity.getType().getClass().getName(), failure);
            payload = MinecraftRecordEncoder.entityPlaceholder(entity, spawn, failure.getClass().getName());
            fingerprint = Long.MIN_VALUE;
        }
        if (offerCore(
                spawn ? CoreRecordType.ENTITY_SPAWN : CoreRecordType.ENTITY_STATE,
                dimensionId(level),
                archiveNanos,
                serverTick,
                payload
        ) == OfferResult.ACCEPTED) {
            entityFingerprints.put(entity.getUUID(), fingerprint);
        }
    }

    private void captureDimensionStates(long archiveNanos, long serverTick) {
        int currentTick = server.getTickCount();
        if (currentTick - lastDimensionStateTick < 20 && lastDimensionStateTick != Integer.MIN_VALUE) {
            return;
        }
        lastDimensionStateTick = currentTick;
        for (ServerLevel level : server.getAllLevels()) {
            try {
                offerCore(
                        CoreRecordType.DIMENSION_STATE,
                        dimensionId(level),
                        archiveNanos,
                        serverTick,
                        MinecraftRecordEncoder.dimensionState(level)
                );
            } catch (Throwable failure) {
                recordCaptureFailure("dimension:" + dimensionId(level), failure);
            }
        }
    }

    private void captureDirtyBlockEntities(long archiveNanos, long serverTick) {
        if (dirtyBlockEntities.isEmpty()) {
            return;
        }
        List<Map.Entry<BlockEntityKey, BlockEntity>> entries = new ArrayList<>(dirtyBlockEntities.entrySet());
        dirtyBlockEntities.clear();
        for (Map.Entry<BlockEntityKey, BlockEntity> entry : entries) {
            BlockEntity blockEntity = entry.getValue();
            ChunkKey chunk = entry.getKey().chunk();
            if (blockEntity.isRemoved() || !observedChunks.contains(chunk)) {
                continue;
            }
            try {
                offerCore(
                        CoreRecordType.BLOCK_ENTITY_STATE,
                        chunk.dimensionId(),
                        archiveNanos,
                        serverTick,
                        MinecraftRecordEncoder.blockEntityState(chunk.level(), blockEntity)
                );
            } catch (Throwable failure) {
                recordCaptureFailure("block_entity:" + blockEntity.getType().getClass().getName(), failure);
            }
        }
    }

    private void captureDirtyLight(long archiveNanos, long serverTick) {
        if (dirtyLightChunks.isEmpty()) {
            return;
        }
        List<ChunkKey> chunks = new ArrayList<>(dirtyLightChunks);
        dirtyLightChunks.clear();
        for (ChunkKey key : chunks) {
            LevelChunk chunk = resolveFullChunk(key);
            if (chunk == null || !observedChunks.contains(key)) {
                continue;
            }
            try {
                offerCore(
                        CoreRecordType.CHUNK_LIGHT,
                        key.dimensionId(),
                        archiveNanos,
                        serverTick,
                        MinecraftRecordEncoder.chunkLight(key.level(), chunk)
                );
            } catch (Throwable failure) {
                recordCaptureFailure("chunk_light:" + key.dimensionId(), failure);
            }
        }
    }

    private void flushChat(long archiveNanos, long serverTick) {
        if (pendingChat.isEmpty()) {
            return;
        }
        LinkedHashMap<ChatKey, List<UUID>> grouped = new LinkedHashMap<>();
        PendingChat delivery;
        while ((delivery = pendingChat.poll()) != null) {
            grouped.computeIfAbsent(
                    new ChatKey(delivery.deliveryToken(), delivery.renderedJson(), delivery.kind()),
                    ignored -> new ArrayList<>()
            ).add(delivery.recipient());
        }
        for (Map.Entry<ChatKey, List<UUID>> entry : grouped.entrySet()) {
            ChatKey key = entry.getKey();
            offerCore(
                    CoreRecordType.CHAT_DELIVERY,
                    "",
                    archiveNanos,
                    serverTick,
                    MinecraftRecordEncoder.chatDelivery(entry.getValue(), key.renderedJson(), key.kind())
            );
        }
    }

    private void flushEntityEffects(long archiveNanos, long serverTick) {
        if (pendingEntityEffects.isEmpty()) {
            return;
        }
        LinkedHashMap<EntityEffectKey, PendingEntityEffect> unique = new LinkedHashMap<>();
        PendingEntityEffect delivery;
        while ((delivery = pendingEntityEffects.poll()) != null) {
            unique.putIfAbsent(
                    new EntityEffectKey(delivery.deliveryToken(), delivery.entityId(), delivery.effect()),
                    delivery
            );
        }
        for (PendingEntityEffect effect : unique.values()) {
            offerCore(
                    CoreRecordType.ENTITY_EFFECT,
                    effect.dimensionId(),
                    archiveNanos,
                    serverTick,
                    MinecraftRecordEncoder.entityEffect(effect.entityId(), effect.effect())
            );
        }
    }

    private OfferResult offerCore(
            CoreRecordType type,
            String dimension,
            long archiveNanos,
            long serverTick,
            byte[] payload
    ) {
        return pipeline.offer(ReplayRecord.core(type, archiveNanos, serverTick, dimension, payload));
    }

    private OfferResult offerControl(CoreRecordType type, long archiveNanos, long serverTick, byte[] payload) {
        return pipeline.offer(ReplayRecord.control(type, archiveNanos, serverTick, payload));
    }

    private long archiveNanos() {
        return Math.max(0, System.nanoTime() - startMonotonicNanos);
    }

    private long serverTick() {
        return Math.max(0, (long) server.getTickCount() - startServerTick);
    }

    private void recordCaptureFailure(String boundary, Throwable failure) {
        int count = captureFailures.merge(boundary, 1, Integer::sum);
        if (count <= 3 || count == 10 || count % 100 == 0) {
            DreamingRecall.LOGGER.warn("Replay capture boundary {} failed (count {})", boundary, count, failure);
        }
    }

    private static String dimensionId(ServerLevel level) {
        return level.dimension().location().toString();
    }

    private record ChunkKey(ServerLevel level, int x, int z) {
        String dimensionId() {
            return ServerRecordingSession.dimensionId(level);
        }
    }

    private record PendingChunk(ChunkKey key, int attempts) {
    }

    private record BlockEntityKey(ChunkKey chunk, long blockPosition) {
    }

    private record PendingChat(int deliveryToken, UUID recipient, String renderedJson, String kind) {
    }

    private record PendingEntityEffect(int deliveryToken, String dimensionId, UUID entityId, String effect) {
    }

    private record EntityEffectKey(int deliveryToken, UUID entityId, String effect) {
    }

    private record ChatKey(int deliveryToken, String renderedJson, String kind) {
    }
}
