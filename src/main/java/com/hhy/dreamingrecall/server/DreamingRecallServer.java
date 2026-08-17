package com.hhy.dreamingrecall.server;

import com.hhy.dreamingrecall.DreamingRecall;
import com.hhy.dreamingrecall.archive.ArchiveManifest;
import com.hhy.dreamingrecall.archive.ArchiveRetentionManager;
import com.hhy.dreamingrecall.api.extension.ExtensionSubmissionResult;
import com.hhy.dreamingrecall.api.extension.ReplayExtension;
import com.hhy.dreamingrecall.config.DreamingRecallConfig;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import com.hhy.dreamingrecall.network.CameraSamplePayload;
import com.hhy.dreamingrecall.network.PlayerVisualSamplePayload;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DreamingRecallServer {
    public static final DreamingRecallServer INSTANCE = new DreamingRecallServer();

    private final Map<MinecraftServer, ServerRecordingSession> sessions = new ConcurrentHashMap<>();

    private DreamingRecallServer() {
    }

    public StartResult startManual(MinecraftServer server) {
        return start(server, RecordingMode.MANUAL);
    }

    public StartResult startAutomatic(MinecraftServer server) {
        return start(server, RecordingMode.AUTOMATIC);
    }

    private StartResult start(MinecraftServer server, RecordingMode mode) {
        if (sessions.containsKey(server)) {
            return StartResult.ALREADY_RECORDING;
        }

        if (mode == RecordingMode.AUTOMATIC && DreamingRecallConfig.AUTOMATIC_QUOTA_MEBIBYTES.get() > 0) {
            try {
                ArchiveRetentionManager.enforce(
                        archiveRoot(server),
                        new ArchiveRetentionManager.Policy(
                                DreamingRecallConfig.AUTOMATIC_QUOTA_MEBIBYTES.get() * 1024L * 1024L,
                                DreamingRecallConfig.RETENTION_MIN_FREE_MEBIBYTES.get() * 1024L * 1024L,
                                0.10
                        )
                );
            } catch (Exception failure) {
                DreamingRecall.LOGGER.warn("Could not enforce replay archive retention before automatic recording", failure);
            }
        }

        ArchiveManifest manifest = ArchiveManifest.create(
                SharedConstants.getCurrentVersion().getName(),
                DreamingRecall.VERSION,
                server.isDedicatedServer()
                        ? ArchiveManifest.SourceKind.DEDICATED_SERVER
                        : ArchiveManifest.SourceKind.SINGLEPLAYER,
                mode == RecordingMode.AUTOMATIC
        );
        ServerRecordingSession session = new ServerRecordingSession(server, archiveRoot(server), manifest, mode);
        ServerRecordingSession existing = sessions.putIfAbsent(server, session);
        if (existing != null) {
            return StartResult.ALREADY_RECORDING;
        }
        try {
            session.start();
            session.whenStopped(() -> {
                if (server.isRunning()) {
                    server.execute(() -> sessions.remove(server, session));
                }
            });
            announce(server, "message.dreamingrecall.recording_started");
            DreamingRecall.LOGGER.info("Started {} DreamingRecall recording for {}", mode, server.getWorldData().getLevelName());
            return StartResult.STARTED;
        } catch (Throwable failure) {
            sessions.remove(server, session);
            DreamingRecall.LOGGER.error("Could not start DreamingRecall recording", failure);
            return StartResult.FAILED;
        }
    }

    public StopResult stop(MinecraftServer server) {
        ServerRecordingSession session = sessions.get(server);
        if (session == null) {
            return StopResult.NOT_RECORDING;
        }
        announce(server, "message.dreamingrecall.recording_stopping");
        session.requestStop();
        return StopResult.STOPPING;
    }

    public Optional<ServerRecordingStatus> status(MinecraftServer server) {
        return Optional.ofNullable(sessions.get(server)).map(ServerRecordingSession::snapshot);
    }

    public void serverStarted(MinecraftServer server) {
        if (DreamingRecallConfig.AUTO_RECORDING.get()) {
            startAutomatic(server);
        }
    }

    public void serverTick(MinecraftServer server) {
        ServerRecordingSession session = sessions.get(server);
        if (session != null) {
            session.tick();
        }
    }

    public void serverStopping(MinecraftServer server) {
        ServerRecordingSession session = sessions.get(server);
        if (session != null) {
            session.requestStop();
        }
    }

    public void serverStopped(MinecraftServer server) {
        ServerRecordingSession session = sessions.remove(server);
        if (session != null) {
            session.requestStop();
            session.awaitStopped(Duration.ofSeconds(30));
        }
    }

    public void chunkLoaded(ServerLevel level, LevelChunk chunk) {
        session(level.getServer()).ifPresent(session -> session.enqueueChunk(level, chunk.getPos().x, chunk.getPos().z));
    }

    public void chunkUnloaded(ServerLevel level, LevelChunk chunk) {
        session(level.getServer()).ifPresent(session -> session.chunkUnloaded(level, chunk.getPos().x, chunk.getPos().z));
    }

    public void blockChanged(LevelChunk chunk, BlockPos pos, BlockState newState) {
        if (chunk.getLevel() instanceof ServerLevel level) {
            session(level.getServer()).ifPresent(session -> session.blockChanged(chunk, pos, newState));
        }
    }

    public void blockEntityChanged(BlockEntity blockEntity) {
        if (blockEntity.getLevel() instanceof ServerLevel level) {
            session(level.getServer()).ifPresent(session -> session.blockEntityChanged(blockEntity));
        }
    }

    public void blockEntityRemoved(LevelChunk chunk, BlockPos pos) {
        if (chunk.getLevel() instanceof ServerLevel level) {
            session(level.getServer()).ifPresent(session -> session.blockEntityRemoved(chunk, pos));
        }
    }

    public void entityJoined(Entity entity) {
        if (entity.level() instanceof ServerLevel level) {
            session(level.getServer()).ifPresent(session -> session.entityJoined(entity));
        }
    }

    public void entityLeft(Entity entity) {
        if (entity.level() instanceof ServerLevel level) {
            session(level.getServer()).ifPresent(session -> session.entityLeft(entity));
        }
    }

    public void chatDelivered(ServerPlayer recipient, Component rendered, String kind, int deliveryToken) {
        MinecraftServer server = recipient.getServer();
        if (server != null) {
            session(server).ifPresent(session -> session.chatDelivered(recipient, rendered, kind, deliveryToken));
        }
    }

    public void entityEffect(
            ServerPlayer recipient,
            UUID entityId,
            String effect,
            int deliveryToken
    ) {
        MinecraftServer server = recipient.getServer();
        if (server != null) {
            session(server).ifPresent(session -> session.entityEffect(
                    recipient,
                    entityId,
                    effect,
                    deliveryToken
            ));
        }
    }

    public void soundPlayed(
            ServerLevel level,
            Holder<SoundEvent> sound,
            SoundSource source,
            Vec3 position,
            float volume,
            float pitch
    ) {
        session(level.getServer()).ifPresent(session -> session.soundPlayed(level, sound, source, position, volume, pitch));
    }

    public void clientCameraSample(ServerPlayer player, CameraSamplePayload sample) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            session(server).ifPresent(session -> session.clientCameraSample(player, sample));
        }
    }

    public void clientPlayerVisualSample(ServerPlayer player, PlayerVisualSamplePayload sample) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            session(server).ifPresent(session -> session.clientPlayerVisualSample(player, sample));
        }
    }

    public ExtensionSubmissionResult submitExtension(
            MinecraftServer server,
            ReplayExtension extension,
            String channel,
            String scope,
            byte[] payload
    ) {
        ServerRecordingSession session = sessions.get(server);
        return session == null
                ? ExtensionSubmissionResult.NOT_RECORDING
                : session.submitExtension(extension, channel, scope, payload);
    }

    public java.util.Optional<com.hhy.dreamingrecall.archive.ArchiveAttachmentStore.AttachmentReference> attachResourcePack(
            MinecraftServer server,
            Path source
    ) throws java.io.IOException {
        ServerRecordingSession session = sessions.get(server);
        return session == null
                ? java.util.Optional.empty()
                : session.attachResourcePack(
                        source,
                        DreamingRecallConfig.RESOURCE_PACK_MAX_MEBIBYTES.get() * 1024L * 1024L
                );
    }

    public Path archiveRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("dreamingrecall")
                .resolve("replays")
                .toAbsolutePath()
                .normalize();
    }

    private Optional<ServerRecordingSession> session(MinecraftServer server) {
        return Optional.ofNullable(sessions.get(server));
    }

    private static void announce(MinecraftServer server, String translationKey) {
        if (DreamingRecallConfig.ANNOUNCE_RECORDING.get()) {
            server.getPlayerList().broadcastSystemMessage(Component.translatable(translationKey), false);
        }
    }

    public enum StartResult {
        STARTED,
        ALREADY_RECORDING,
        FAILED
    }

    public enum StopResult {
        STOPPING,
        NOT_RECORDING
    }
}
