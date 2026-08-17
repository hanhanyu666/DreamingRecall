package com.hhy.dreamingrecall.client.recording;

import com.hhy.dreamingrecall.DreamingRecall;
import com.hhy.dreamingrecall.archive.ArchiveManifest;
import com.hhy.dreamingrecall.archive.CoreRecordType;
import com.hhy.dreamingrecall.archive.RecordPriority;
import com.hhy.dreamingrecall.archive.ReplayRecord;
import com.hhy.dreamingrecall.archive.packet.PacketEnvelope;
import com.hhy.dreamingrecall.archive.packet.PacketEnvelopeCodec;
import com.hhy.dreamingrecall.archive.packet.PacketScope;
import com.hhy.dreamingrecall.archive.packet.ProtocolPhase;
import com.hhy.dreamingrecall.archive.registry.RegistryManifestCodec;
import com.hhy.dreamingrecall.archive.registry.RuntimeRegistryManifest;
import com.hhy.dreamingrecall.archive.track.TrackNames;
import com.hhy.dreamingrecall.capture.BinaryPayloads;
import com.hhy.dreamingrecall.capture.CaptureBridge;
import com.hhy.dreamingrecall.capture.MinecraftRecordEncoder;
import com.hhy.dreamingrecall.client.library.ClientArchiveLibrary;
import com.hhy.dreamingrecall.client.playback.ReplayWorldController;
import com.hhy.dreamingrecall.client.playback.packet.PacketReplayViewController;
import com.hhy.dreamingrecall.client.playback.packet.ReplayPacketDispatchContext;
import com.hhy.dreamingrecall.config.DreamingRecallClientConfig;
import com.hhy.dreamingrecall.network.CameraSamplePayload;
import com.hhy.dreamingrecall.network.PlayerVisualSamplePayload;
import com.hhy.dreamingrecall.recording.OfferResult;
import com.hhy.dreamingrecall.recording.PipelineState;
import com.hhy.dreamingrecall.recording.RecordingPipeline;
import com.hhy.dreamingrecall.recording.RecordingSettings;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.login.ClientboundGameProfilePacket;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

public final class ClientRecordingManager implements CaptureBridge.ClientSink {
    public static final ClientRecordingManager INSTANCE = new ClientRecordingManager();

    private volatile ClientSession session;

    private ClientRecordingManager() {
    }

    public boolean isRecording() {
        return session != null && session.pipeline.state() != PipelineState.FAILED;
    }

    public synchronized void start(Minecraft minecraft) {
        start(minecraft, true);
    }

    private void start(Minecraft minecraft, boolean announce) {
        if (session != null
                || ReplayPacketDispatchContext.isActive()
                || minecraft.level != null && (ReplayWorldController.isReplayLevel(minecraft.level)
                || PacketReplayViewController.isReplayLevel(minecraft.level))) {
            return;
        }
        Path archiveRoot = ClientArchiveLibrary.importedArchiveRoot(minecraft.gameDirectory.toPath());
        ArchiveManifest manifest = ArchiveManifest.create(
                SharedConstants.getCurrentVersion().getName(),
                DreamingRecall.VERSION,
                minecraft.getSingleplayerServer() == null
                        ? ArchiveManifest.SourceKind.CLIENT_MULTIPLAYER
                        : ArchiveManifest.SourceKind.SINGLEPLAYER
        );
        RecordingPipeline pipeline = new RecordingPipeline(
                archiveRoot,
                manifest,
                RecordingSettings.defaults(),
                failure -> DreamingRecall.LOGGER.error("Client replay writer failed", failure)
        );
        ClientSession created = new ClientSession(pipeline);
        session = created;
        pipeline.start();
        if (minecraft.level != null) {
            created.switchLevel(minecraft, minecraft.level, "initial");
        }
        if (announce && minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.translatable("message.dreamingrecall.client_recording_started"),
                    false
            );
        }
    }

    public synchronized void stop(Minecraft minecraft) {
        ClientSession current = session;
        session = null;
        if (current == null) {
            return;
        }
        current.stop();
        current.pipeline.stoppedFuture().whenComplete((path, failure) -> {
            if (failure != null) {
                DreamingRecall.LOGGER.error("Failed to finish client replay archive", failure);
            } else {
                DreamingRecall.LOGGER.info("Finished client replay archive at {}", path);
            }
        });
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(
                    Component.translatable("message.dreamingrecall.client_recording_stopping"),
                    false
            );
        }
    }

    public void tick(Minecraft minecraft) {
        ClientSession current = session;
        if (current == null) {
            return;
        }
        if (minecraft.level != null && minecraft.player != null) {
            current.tick(minecraft);
        }
        if (current.pipeline.state() == PipelineState.FAILED) {
            session = null;
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("message.dreamingrecall.client_recording_failed"),
                        false
                );
            }
        }
    }

    public void chunkLoaded(ClientLevel level, LevelChunk chunk) {
        ClientSession current = session;
        if (current != null) {
            current.chunkLoaded(level, chunk);
        }
    }

    public void chunkUnloaded(ClientLevel level, LevelChunk chunk) {
        ClientSession current = session;
        if (current != null) {
            current.chunkUnloaded(level, chunk);
        }
    }

    public void blockChanged(LevelChunk chunk, BlockPos position, BlockState state) {
        ClientSession current = session;
        if (current != null && chunk.getLevel() instanceof ClientLevel level) {
            current.blockChanged(level, position, state);
        }
    }

    public void blockEntityChanged(BlockEntity blockEntity) {
        ClientSession current = session;
        if (current != null && blockEntity.getLevel() instanceof ClientLevel level) {
            current.blockEntityChanged(level, blockEntity);
        }
    }

    public void blockEntityRemoved(LevelChunk chunk, BlockPos position) {
        ClientSession current = session;
        if (current != null && chunk.getLevel() instanceof ClientLevel level) {
            current.blockEntityRemoved(level, position);
        }
    }

    public void entityJoined(ClientLevel level, Entity entity) {
        ClientSession current = session;
        if (current != null) {
            current.entityJoined(level, entity);
        }
    }

    public void entityLeft(ClientLevel level, Entity entity) {
        ClientSession current = session;
        if (current != null) {
            current.entityLeft(level, entity);
        }
    }

    public void chatReceived(Component message, String kind) {
        ClientSession current = session;
        if (current != null) {
            current.chatReceived(message, kind);
        }
    }

    public void soundPlayed(
            ClientLevel level,
            Holder<SoundEvent> sound,
            SoundSource source,
            Vec3 position,
            float volume,
            float pitch
    ) {
        ClientSession current = session;
        if (current != null) {
            current.soundPlayed(level, sound, source, position, volume, pitch);
        }
    }

    public void entityEffect(ClientLevel level, UUID entityId, String effect) {
        ClientSession current = session;
        if (current != null) {
            current.entityEffect(level, entityId, effect);
        }
    }

    public void playerVisualSample(long clientNanos, CameraSamplePayload.PlayerVisual visual) {
        ClientSession current = session;
        if (current != null) {
            current.playerVisualSample(clientNanos, visual);
        }
    }

    public void cameraSample(CameraSamplePayload sample) {
        ClientSession current = session;
        if (current != null) {
            current.cameraSample(sample);
        }
    }

    /**
     * Called from the client Netty decoder before packet handling. The supplied
     * frame already contains Minecraft's packet id followed by its payload.
     */
    public void inboundPacket(ProtocolInfo<?> protocol, Packet<?> packet, byte[] frame) {
        ProtocolPhase phase = protocolPhase(protocol.id());
        if (phase == null) {
            return;
        }
        ClientSession current = session;
        if (current == null) {
            // RECORD_ON_JOIN controls automatic session creation. Once a local
            // session was started manually, packet capture must remain active
            // even if the menu toggle is later turned off.
            if (!DreamingRecallClientConfig.RECORD_ON_JOIN.get()) {
                return;
            }
            if (phase == ProtocolPhase.LOGIN && !(packet instanceof ClientboundGameProfilePacket)) {
                return;
            }
            synchronized (this) {
                if (session == null) {
                    start(Minecraft.getInstance(), false);
                }
                current = session;
            }
        }
        if (current != null) {
            current.inboundPacket(phase, packet, frame);
        }
    }

    private static ProtocolPhase protocolPhase(ConnectionProtocol protocol) {
        return switch (protocol) {
            case LOGIN -> ProtocolPhase.LOGIN;
            case CONFIGURATION -> ProtocolPhase.CONFIGURATION;
            case PLAY -> ProtocolPhase.PLAY;
            default -> null;
        };
    }

    private static final class ClientSession {
        private static final int CHUNKS_PER_TICK = 1;
        private static final int ENTITIES_PER_TICK = 128;

        private final RecordingPipeline pipeline;
        private final long startedNanos = System.nanoTime();
        private final Map<ChunkKey, LevelChunk> pendingChunks = new LinkedHashMap<>();
        private final Set<ChunkKey> observedChunks = new HashSet<>();
        private final Map<UUID, Entity> trackedEntities = new LinkedHashMap<>();
        private final Map<UUID, Long> entityFingerprints = new HashMap<>();
        private final ArrayList<UUID> entityOrder = new ArrayList<>();

        private ClientLevel level;
        private String dimensionId = "";
        private volatile long tick;
        private volatile String packetDimensionId = "";
        private boolean registryManifestScheduled;
        private int entityCursor;
        private boolean baselineRunning;
        private boolean baselineBeginAccepted;
        private long baselineGeneration = -1;

        private ClientSession(RecordingPipeline pipeline) {
            this.pipeline = pipeline;
        }

        private void tick(Minecraft minecraft) {
            tick++;
            if (minecraft.level != level) {
                switchLevel(minecraft, minecraft.level, "dimension_change");
            }
            long archiveNanos = archiveNanos();
            offerCore(CoreRecordType.SERVER_TICK, "", new byte[0], archiveNanos);

            if (pipeline.requiresBaseline()
                    && (!baselineRunning || baselineGeneration != pipeline.baselineGeneration())) {
                beginBaseline(minecraft, "recorder_overload_recovery", pipeline.baselineGeneration());
            }
            if (baselineRunning && !baselineBeginAccepted) {
                baselineBeginAccepted = offerControl(
                        CoreRecordType.BASELINE_BEGIN,
                        "baseline_retry".getBytes(StandardCharsets.UTF_8),
                        archiveNanos
                ) == OfferResult.ACCEPTED;
                if (baselineBeginAccepted) {
                    seedBaseline(minecraft);
                }
            }

            capturePlayers(minecraft, archiveNanos);
            captureEntities(archiveNanos);
            if (tick % 20 == 0) {
                offerCore(
                        CoreRecordType.DIMENSION_STATE,
                        dimensionId,
                        MinecraftRecordEncoder.dimensionState(level),
                        archiveNanos
                );
            }
            capturePendingChunks(archiveNanos);
            if (baselineRunning && baselineBeginAccepted && pendingChunks.isEmpty()) {
                completeBaseline(archiveNanos);
            }
        }

        private void switchLevel(Minecraft minecraft, ClientLevel next, String reason) {
            level = next;
            dimensionId = next.dimension().location().toString();
            packetDimensionId = dimensionId;
            scheduleRegistryManifest(next);
            beginBaseline(minecraft, reason, -1);
        }

        private void scheduleRegistryManifest(ClientLevel currentLevel) {
            if (registryManifestScheduled) {
                return;
            }
            registryManifestScheduled = true;
            var manifest = RuntimeRegistryManifest.capture(currentLevel.registryAccess());
            pipeline.readyFuture().thenAcceptAsync(directory -> {
                try {
                    RegistryManifestCodec.write(directory, manifest);
                } catch (java.io.IOException failure) {
                    throw new CompletionException(failure);
                }
            }, command -> Thread.startVirtualThread(command)).exceptionally(failure -> {
                DreamingRecall.LOGGER.warn("Could not write replay registry manifest", failure);
                return null;
            });
        }

        private void inboundPacket(ProtocolPhase phase, Packet<?> packet, byte[] frame) {
            try {
                String packetId = packet.type().id().toString();
                String namespace = packet instanceof ClientboundCustomPayloadPacket custom
                        ? custom.payload().type().id().getNamespace()
                        : packet.type().id().getNamespace();
                UUID playerId = Minecraft.getInstance().getUser().getProfileId();
                String track = phase == ProtocolPhase.PLAY
                        ? TrackNames.playerClient(playerId)
                        : TrackNames.CONFIGURATION;
                PacketEnvelope envelope = new PacketEnvelope(
                        PacketEnvelope.CURRENT_SCHEMA_VERSION,
                        track,
                        phase,
                        packetId,
                        namespace,
                        phase == ProtocolPhase.PLAY ? PacketScope.CLIENT_LOCAL : PacketScope.SESSION,
                        phase == ProtocolPhase.PLAY ? packetDimensionId : "",
                        phase == ProtocolPhase.PLAY ? playerId : null,
                        null,
                        "",
                        frame
                );
                offerCore(
                        CoreRecordType.PACKET_FRAME,
                        envelope.dimensionId(),
                        PacketEnvelopeCodec.encode(envelope),
                        archiveNanos()
                );
            } catch (Throwable failure) {
                DreamingRecall.LOGGER.warn(
                        "Could not capture clientbound replay packet {}",
                        packet.type().id(),
                        failure
                );
            }
        }

        private void beginBaseline(Minecraft minecraft, String reason, long generation) {
            baselineRunning = true;
            baselineGeneration = generation;
            pendingChunks.clear();
            observedChunks.clear();
            trackedEntities.clear();
            entityFingerprints.clear();
            entityOrder.clear();
            entityCursor = 0;
            baselineBeginAccepted = offerControl(
                    CoreRecordType.BASELINE_BEGIN,
                    reason.getBytes(StandardCharsets.UTF_8),
                    archiveNanos()
            ) == OfferResult.ACCEPTED;
            if (baselineBeginAccepted) {
                seedBaseline(minecraft);
            }
        }

        private void seedBaseline(Minecraft minecraft) {
            long archiveNanos = archiveNanos();
            offerCore(
                    CoreRecordType.DIMENSION_STATE,
                    dimensionId,
                    MinecraftRecordEncoder.dimensionState(level),
                    archiveNanos
            );
            enqueueLoadedChunks(minecraft);
            for (Entity entity : level.entitiesForRendering()) {
                entityJoined(level, entity);
            }
        }

        private void enqueueLoadedChunks(Minecraft minecraft) {
            int centerX = minecraft.player == null ? 0 : minecraft.player.chunkPosition().x;
            int centerZ = minecraft.player == null ? 0 : minecraft.player.chunkPosition().z;
            int radius = minecraft.options.getEffectiveRenderDistance() + 2;
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                for (int x = centerX - radius; x <= centerX + radius; x++) {
                    LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                    if (chunk != null) {
                        chunkLoaded(level, chunk);
                    }
                }
            }
        }

        private void capturePlayers(Minecraft minecraft, long archiveNanos) {
            for (Player player : level.players()) {
                try {
                    PlayerInfo info = minecraft.getConnection() == null
                            ? null
                            : minecraft.getConnection().getPlayerInfo(player.getUUID());
                    String gameMode = info == null ? "survival" : info.getGameMode().getName();
                    offerCore(
                            CoreRecordType.PLAYER_STATE,
                            dimensionId,
                            MinecraftRecordEncoder.playerState(player, gameMode),
                            archiveNanos
                    );
                } catch (Throwable failure) {
                    DreamingRecall.LOGGER.debug("Could not capture client player {}", player.getUUID(), failure);
                }
            }
        }

        private void captureEntities(long archiveNanos) {
            if (entityOrder.isEmpty()) {
                return;
            }
            int budget = Math.min(ENTITIES_PER_TICK, entityOrder.size());
            for (int index = 0; index < budget && !entityOrder.isEmpty(); index++) {
                if (entityCursor >= entityOrder.size()) {
                    entityCursor = 0;
                }
                UUID uuid = entityOrder.get(entityCursor++);
                Entity entity = trackedEntities.get(uuid);
                if (entity == null || entity.isRemoved() || entity instanceof Player) {
                    continue;
                }
                try {
                    long fingerprint = MinecraftRecordEncoder.entityFingerprint(entity);
                    Long previous = entityFingerprints.put(uuid, fingerprint);
                    if (previous == null || previous.longValue() != fingerprint) {
                        offerCore(
                                CoreRecordType.ENTITY_STATE,
                                dimensionId,
                                MinecraftRecordEncoder.entityState(entity, false),
                                archiveNanos
                        );
                    }
                } catch (Throwable failure) {
                    offerCore(
                            CoreRecordType.ENTITY_STATE,
                            dimensionId,
                            MinecraftRecordEncoder.entityPlaceholder(entity, false, failure.getClass().getName()),
                            archiveNanos
                    );
                }
            }
        }

        private void capturePendingChunks(long archiveNanos) {
            for (int index = 0; index < CHUNKS_PER_TICK && !pendingChunks.isEmpty(); index++) {
                Map.Entry<ChunkKey, LevelChunk> entry = pendingChunks.entrySet().iterator().next();
                pendingChunks.remove(entry.getKey());
                LevelChunk chunk = entry.getValue();
                byte[] payload;
                try {
                    payload = MinecraftRecordEncoder.chunkBaseline(level, chunk);
                } catch (Throwable failure) {
                    payload = MinecraftRecordEncoder.chunkPlaceholder(chunk, failure.getClass().getName());
                }
                OfferResult result = offerCore(CoreRecordType.CHUNK_BASELINE, dimensionId, payload, archiveNanos);
                if (result == OfferResult.ACCEPTED) {
                    observedChunks.add(entry.getKey());
                } else if (pipeline.state() != PipelineState.FAILED) {
                    pendingChunks.put(entry.getKey(), chunk);
                }
            }
        }

        private void completeBaseline(long archiveNanos) {
            if (offerControl(CoreRecordType.BASELINE_END, new byte[0], archiveNanos) != OfferResult.ACCEPTED) {
                return;
            }
            if (baselineGeneration >= 0) {
                pipeline.markBaselineComplete(baselineGeneration);
            }
            baselineRunning = false;
        }

        private void chunkLoaded(ClientLevel eventLevel, LevelChunk chunk) {
            if (eventLevel != level) {
                return;
            }
            ChunkKey key = new ChunkKey(chunk.getPos().x, chunk.getPos().z);
            if (!observedChunks.contains(key)) {
                pendingChunks.putIfAbsent(key, chunk);
            }
        }

        private void chunkUnloaded(ClientLevel eventLevel, LevelChunk chunk) {
            if (eventLevel != level) {
                return;
            }
            ChunkKey key = new ChunkKey(chunk.getPos().x, chunk.getPos().z);
            pendingChunks.remove(key);
            if (observedChunks.remove(key)) {
                offerCore(
                        CoreRecordType.CHUNK_OBSERVATION_END,
                        dimensionId,
                        BinaryPayloads.chunkCoordinates(key.x, key.z),
                        archiveNanos()
                );
            }
        }

        private void blockChanged(ClientLevel eventLevel, BlockPos position, BlockState state) {
            if (eventLevel == level) {
                offerCore(
                        CoreRecordType.BLOCK_CHANGE,
                        dimensionId,
                        MinecraftRecordEncoder.blockChange(position, state),
                        archiveNanos()
                );
            }
        }

        private void blockEntityChanged(ClientLevel eventLevel, BlockEntity blockEntity) {
            if (eventLevel != level) {
                return;
            }
            try {
                offerCore(
                        CoreRecordType.BLOCK_ENTITY_STATE,
                        dimensionId,
                        MinecraftRecordEncoder.blockEntityState(level, blockEntity),
                        archiveNanos()
                );
            } catch (Throwable failure) {
                DreamingRecall.LOGGER.debug("Could not capture client block entity", failure);
            }
        }

        private void blockEntityRemoved(ClientLevel eventLevel, BlockPos position) {
            if (eventLevel == level) {
                offerCore(
                        CoreRecordType.BLOCK_ENTITY_REMOVE,
                        dimensionId,
                        BinaryPayloads.blockPosition(position.asLong()),
                        archiveNanos()
                );
            }
        }

        private void entityJoined(ClientLevel eventLevel, Entity entity) {
            if (eventLevel != level || entity instanceof Player || trackedEntities.containsKey(entity.getUUID())) {
                return;
            }
            trackedEntities.put(entity.getUUID(), entity);
            entityOrder.add(entity.getUUID());
            try {
                entityFingerprints.put(entity.getUUID(), MinecraftRecordEncoder.entityFingerprint(entity));
                offerCore(
                        CoreRecordType.ENTITY_SPAWN,
                        dimensionId,
                        MinecraftRecordEncoder.entityState(entity, true),
                        archiveNanos()
                );
            } catch (Throwable failure) {
                offerCore(
                        CoreRecordType.ENTITY_SPAWN,
                        dimensionId,
                        MinecraftRecordEncoder.entityPlaceholder(entity, true, failure.getClass().getName()),
                        archiveNanos()
                );
            }
        }

        private void entityLeft(ClientLevel eventLevel, Entity entity) {
            if (eventLevel != level) {
                return;
            }
            if (trackedEntities.remove(entity.getUUID()) != null) {
                entityFingerprints.remove(entity.getUUID());
                entityOrder.remove(entity.getUUID());
            }
            offerCore(
                    CoreRecordType.ENTITY_REMOVE,
                    dimensionId,
                    MinecraftRecordEncoder.entityRemoved(entity),
                    archiveNanos()
            );
        }

        private void chatReceived(Component message, String kind) {
            try {
                String json = Component.Serializer.toJson(message, level.registryAccess());
                List<UUID> recipients = Minecraft.getInstance().player == null
                        ? List.of()
                        : List.of(Minecraft.getInstance().player.getUUID());
                offerCore(
                        CoreRecordType.CHAT_DELIVERY,
                        dimensionId,
                        MinecraftRecordEncoder.chatDelivery(recipients, json, kind),
                        archiveNanos()
                );
            } catch (Throwable failure) {
                DreamingRecall.LOGGER.debug("Could not capture client chat entry", failure);
            }
        }

        private void soundPlayed(
                ClientLevel eventLevel,
                Holder<SoundEvent> sound,
                SoundSource source,
                Vec3 position,
                float volume,
                float pitch
        ) {
            if (eventLevel == level) {
                offerCore(
                        CoreRecordType.GAME_SOUND,
                        dimensionId,
                        MinecraftRecordEncoder.sound(sound, source, position, volume, pitch),
                        archiveNanos()
                );
            }
        }

        private void entityEffect(ClientLevel eventLevel, UUID entityId, String effect) {
            if (eventLevel == level) {
                offerCore(
                        CoreRecordType.ENTITY_EFFECT,
                        dimensionId,
                        MinecraftRecordEncoder.entityEffect(entityId, effect),
                        archiveNanos()
                );
            }
        }

        private void playerVisualSample(long clientNanos, CameraSamplePayload.PlayerVisual visual) {
            Player player = Minecraft.getInstance().player;
            if (player == null) {
                return;
            }
            PlayerVisualSamplePayload sample = new PlayerVisualSamplePayload(clientNanos, visual);
            pipeline.offer(new ReplayRecord(
                    CoreRecordType.CLIENT_PLAYER_VISUAL_SAMPLE.id(),
                    RecordPriority.ENHANCEMENT,
                    archiveNanos(),
                    tick,
                    dimensionId,
                    MinecraftRecordEncoder.clientPlayerVisualSample(player.getUUID(), sample)
            ));
        }

        private void cameraSample(CameraSamplePayload sample) {
            Player player = Minecraft.getInstance().player;
            if (player == null) {
                return;
            }
            pipeline.offer(new ReplayRecord(
                    CoreRecordType.CLIENT_CAMERA_SAMPLE.id(),
                    RecordPriority.ENHANCEMENT,
                    archiveNanos(),
                    tick,
                    dimensionId,
                    MinecraftRecordEncoder.clientCameraSample(player.getUUID(), sample)
            ));
        }

        private OfferResult offerCore(CoreRecordType type, String dimension, byte[] payload, long archiveNanos) {
            return pipeline.offer(ReplayRecord.core(type, archiveNanos, tick, dimension, payload));
        }

        private OfferResult offerControl(CoreRecordType type, byte[] payload, long archiveNanos) {
            return pipeline.offer(ReplayRecord.control(type, archiveNanos, tick, payload));
        }

        private void stop() {
            pipeline.requestStop(archiveNanos(), tick);
        }

        private long archiveNanos() {
            return Math.max(0, System.nanoTime() - startedNanos);
        }

        private record ChunkKey(int x, int z) {
        }
    }
}
