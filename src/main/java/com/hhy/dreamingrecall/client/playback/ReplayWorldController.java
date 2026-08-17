package com.hhy.dreamingrecall.client.playback;

import com.hhy.dreamingrecall.director.CameraPose;
import com.hhy.dreamingrecall.api.lod.ReplayLodAdapterRegistry;
import com.hhy.dreamingrecall.mixin.ClientPacketListenerAccessor;
import com.hhy.dreamingrecall.mixin.MinecraftAccessor;
import com.hhy.dreamingrecall.playback.decode.DecodedPayload;
import com.hhy.dreamingrecall.playback.state.ReplayMotionInterpolator;
import com.hhy.dreamingrecall.playback.state.ReplayPlaybackFrame;
import com.hhy.dreamingrecall.playback.state.ReplayWorldSnapshot;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.KeyboardInput;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.telemetry.TelemetryEventSender;
import net.minecraft.client.telemetry.WorldSessionTelemetryManager;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.ServerLinks;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.StatsCounter;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class ReplayWorldController implements AutoCloseable {
    private static final int VIEW_DISTANCE = 16;
    private static final long TICK_NANOS = 50_000_000L;
    private static final int CAMERA_ENTITY_ID = 1_900_000_000;
    private static final UUID CAMERA_UUID = UUID.nameUUIDFromBytes(
            "DreamingRecall camera".getBytes(StandardCharsets.UTF_8)
    );

    /**
     * The active replay level is rendered by Minecraft as a normal ClientLevel,
     * but its entities are snapshots, not live game objects.  Keeping this
     * reference lets the client mixin skip vanilla/NeoForge entity ticking for
     * this one isolated level without affecting a real world.
     */
    private static volatile ClientLevel ACTIVE_LEVEL;
    private static volatile ReplayWorldController ACTIVE_CONTROLLER;

    private final Minecraft minecraft;
    private final RegistryAccess.Frozen registries;
    private final CameraType previousCameraType;
    private final int previousFov;
    private final ClientPacketListener connection;
    private final Map<UUID, Entity> replayEntities = new HashMap<>();
    private final Map<UUID, DecodedPayload.EntityState> renderedEntityStates = new HashMap<>();
    private final Map<UUID, DecodedPayload.PlayerState> renderedPlayerStates = new HashMap<>();
    private final Map<UUID, FallbackWalkState> fallbackWalkStates = new HashMap<>();
    private final Map<UUID, FallbackLivingState> fallbackLivingStates = new HashMap<>();
    private final Map<UUID, RecordedLivingClock> recordedLivingClocks = new HashMap<>();
    private final Map<ReplayWorldSnapshot.ChunkKey, ReplayWorldSnapshot.ChunkSnapshot> renderedChunks = new HashMap<>();
    private final Map<ReplayWorldSnapshot.ChunkKey, ReplayChunkBuilder.ChunkBuildResult> chunkBuildResults = new HashMap<>();
    private final List<ReplayLodAdapterRegistry.SessionHandle> lodSessions;

    private ReplayWorldSnapshot snapshot;
    private ReplayPlaybackFrame playbackFrame;
    private ClientLevel level;
    private LocalPlayer cameraPlayer;
    private String activeDimension;
    private CameraPose freeCamera;
    private CameraMode cameraMode = CameraMode.FREE;
    private UUID attachedPlayer;
    private int nextEntityId = 2;
    private int centerChunkX = Integer.MIN_VALUE;
    private int centerChunkZ = Integer.MIN_VALUE;
    private int degradedBlocks;
    private int failedChunks;
    private int failedBlockEntities;
    private boolean closed;

    public ReplayWorldController(Minecraft minecraft, ReplayWorldSnapshot initialSnapshot) {
        this(minecraft, initialSnapshot, UUID.randomUUID());
    }

    public ReplayWorldController(
            Minecraft minecraft,
            ReplayWorldSnapshot initialSnapshot,
            UUID archiveId
    ) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.snapshot = Objects.requireNonNull(initialSnapshot, "initialSnapshot");
        if (minecraft.level != null || minecraft.player != null) {
            throw new IllegalStateException("Disconnect from the current world before opening a replay");
        }
        this.registries = ReplayRegistryAccess.get();
        this.previousCameraType = minecraft.options.getCameraType();
        this.previousFov = minecraft.options.fov().get();
        this.connection = createConnection();
        this.lodSessions = new ArrayList<>(ReplayLodAdapterRegistry.open(
                Objects.requireNonNull(archiveId, "archiveId"),
                minecraft.gameDirectory.toPath()
        ));
        String dimension = chooseInitialDimension(initialSnapshot);
        CameraPose seed = seedCamera(initialSnapshot, dimension);
        this.freeCamera = seed;
        rebuildLevel(dimension);
        applySnapshot(initialSnapshot);
        ACTIVE_CONTROLLER = this;
    }

    public static boolean isReplayLevel(ClientLevel candidate) {
        return candidate != null && ACTIVE_LEVEL == candidate;
    }

    public static boolean shouldSuppressReplayEntityRender(Entity candidate) {
        ReplayWorldController controller = ACTIVE_CONTROLLER;
        if (controller == null || controller.closed) {
            return false;
        }
        if (candidate == controller.cameraPlayer) {
            return true;
        }
        return controller.cameraMode == CameraMode.FIRST_PERSON
                && controller.attachedPlayer != null
                && candidate == controller.replayEntities.get(controller.attachedPlayer);
    }

    public static void prepareActiveAnimations(float partialTick) {
        ReplayWorldController controller = ACTIVE_CONTROLLER;
        if (controller != null && !controller.closed) {
            controller.prepareAnimations(partialTick);
        }
    }

    public static void setReplayHandRenderPhase(LocalPlayer player, boolean rendering) {
        ReplayWorldController controller = ACTIVE_CONTROLLER;
        if (controller != null
                && !controller.closed
                && controller.cameraMode == CameraMode.FIRST_PERSON
                && controller.cameraPlayer == player) {
            player.setInvisible(!rendering);
        }
    }

    public ApplyResult applySnapshot(ReplayWorldSnapshot next) {
        ensureOpen();
        playbackFrame = null;
        snapshot = Objects.requireNonNull(next, "next");
        if (!next.dimensions().containsKey(activeDimension)) {
            String replacement = chooseInitialDimension(next);
            freeCamera = seedCamera(next, replacement);
            rebuildLevel(replacement);
        }
        applyEnvironment();
        syncEntities();
        updateCameraEntity();
        refreshWorkingSet(false);
        notifyLodSnapshot(next);
        return result();
    }

    public ApplyResult applyPlaybackFrame(ReplayPlaybackFrame frame) {
        ensureOpen();
        playbackFrame = Objects.requireNonNull(frame, "frame");
        snapshot = frame.snapshot();
        boolean rebuilt = false;
        if (!snapshot.dimensions().containsKey(activeDimension)) {
            String replacement = chooseInitialDimension(snapshot);
            freeCamera = seedCamera(snapshot, replacement);
            rebuildLevel(replacement);
            rebuilt = true;
        }
        if (rebuilt || frame.worldStateChanged()) {
            applyEnvironment();
            syncEntities();
            refreshWorkingSet(rebuilt);
            notifyLodSnapshot(snapshot);
        } else {
            syncInterpolatedPlayers();
        }
        updateCameraEntity();
        return result();
    }

    public ApplyResult setDimension(String dimensionId) {
        ensureOpen();
        if (!snapshot.dimensions().containsKey(dimensionId)) {
            throw new IllegalArgumentException("Replay dimension is not present: " + dimensionId);
        }
        if (!dimensionId.equals(activeDimension)) {
            freeCamera = seedCamera(snapshot, dimensionId);
            rebuildLevel(dimensionId);
            applyEnvironment();
            syncEntities();
            updateCameraEntity();
            refreshWorkingSet(true);
        }
        return result();
    }

    public void setCameraMode(CameraMode mode) {
        ensureOpen();
        CameraMode requested = Objects.requireNonNull(mode, "mode");
        if (requested == CameraMode.FREE && cameraMode != CameraMode.FREE) {
            captureRenderedCamera();
        }
        cameraMode = requested;
        if (mode != CameraMode.FREE && attachedPlayer == null) {
            attachedPlayer = playerTargets().stream().findFirst().map(PlayerTarget::uuid).orElse(null);
        }
        updateCameraEntity();
        if (mode == CameraMode.FREE) {
            refreshWorkingSet(false);
        }
    }

    public void attachPlayer(UUID playerId, boolean firstPerson) {
        ensureOpen();
        attachedPlayer = Objects.requireNonNull(playerId, "playerId");
        cameraMode = firstPerson ? CameraMode.FIRST_PERSON : CameraMode.PLAYER;
        PlayerTarget target = playerTargets().stream()
                .filter(value -> value.uuid().equals(playerId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Replay player is not present: " + playerId));
        if (!target.dimensionId().equals(activeDimension)) {
            setDimension(target.dimensionId());
        }
        updateCameraEntity();
    }

    public void moveFreeCamera(double forward, double strafe, double vertical, boolean fast) {
        moveFreeCamera(forward, strafe, vertical, fast, 0.05);
    }

    public void moveFreeCamera(
            double forward,
            double strafe,
            double vertical,
            boolean fast,
            double elapsedSeconds
    ) {
        ensureOpen();
        if (cameraMode != CameraMode.FREE) {
            return;
        }
        double speed = (fast ? 40.0 : 7.0) * Math.max(0.0, Math.min(0.25, elapsedSeconds));
        double yaw = Math.toRadians(freeCamera.yaw());
        double x = (-Math.sin(yaw) * forward + Math.cos(yaw) * strafe) * speed;
        double z = (Math.cos(yaw) * forward + Math.sin(yaw) * strafe) * speed;
        freeCamera = new CameraPose(
                activeDimension,
                freeCamera.x() + x,
                freeCamera.y() + vertical * speed,
                freeCamera.z() + z,
                freeCamera.yaw(),
                freeCamera.pitch(),
                freeCamera.roll(),
                freeCamera.fov()
        );
        updateCameraEntity();
        refreshWorkingSet(false);
    }

    public void turnFreeCamera(float deltaYaw, float deltaPitch) {
        ensureOpen();
        if (cameraMode != CameraMode.FREE) {
            return;
        }
        freeCamera = new CameraPose(
                activeDimension,
                freeCamera.x(),
                freeCamera.y(),
                freeCamera.z(),
                Mth.wrapDegrees(freeCamera.yaw() + deltaYaw),
                Mth.clamp(freeCamera.pitch() + deltaPitch, -89.9F, 89.9F),
                freeCamera.roll(),
                freeCamera.fov()
        );
        updateCameraEntity();
    }

    public List<PlayerTarget> playerTargets() {
        ArrayList<PlayerTarget> targets = new ArrayList<>();
        snapshot.dimensions().forEach((dimensionId, dimension) -> dimension.players().values().forEach(player ->
                targets.add(new PlayerTarget(player.uuid(), player.name(), dimensionId))
        ));
        return List.copyOf(targets);
    }

    public List<String> dimensions() {
        return List.copyOf(snapshot.dimensions().keySet());
    }

    public String activeDimension() {
        return activeDimension;
    }

    public CameraMode cameraMode() {
        return cameraMode;
    }

    public Optional<PlayerTarget> attachedPlayerTarget() {
        if (cameraMode == CameraMode.FREE || attachedPlayer == null) {
            return Optional.empty();
        }
        return playerTargets().stream()
                .filter(target -> target.uuid().equals(attachedPlayer))
                .findFirst();
    }

    public CameraPose freeCamera() {
        return freeCamera;
    }

    /** Returns the current camera pose in archive-friendly coordinates. */
    public CameraPose cameraPose() {
        if (cameraMode == CameraMode.FREE || attachedPlayer == null) {
            return freeCamera;
        }
        Entity target = replayEntities.get(attachedPlayer);
        if (target == null) {
            return freeCamera;
        }
        DecodedPayload.CameraSample sample = attachedCameraSample().orElse(null);
        if (sample != null) {
            return new CameraPose(
                    activeDimension,
                    sample.x(),
                    sample.y(),
                    sample.z(),
                    sample.yaw(),
                    sample.pitch(),
                    sample.roll(),
                    sample.fov()
            );
        }
        Vec3 eye = target.getEyePosition();
        return new CameraPose(
                activeDimension,
                eye.x(),
                eye.y(),
                eye.z(),
                target.getYRot(),
                target.getXRot(),
                0.0F,
                currentFov()
        );
    }

    /** Applies a director pose and switches to a non-physical free camera. */
    public void applyDirectorPose(CameraPose pose) {
        ensureOpen();
        Objects.requireNonNull(pose, "pose");
        if (!snapshot.dimensions().containsKey(pose.dimensionId())) {
            return;
        }
        if (!pose.dimensionId().equals(activeDimension)) {
            rebuildLevel(pose.dimensionId());
            applyEnvironment();
            syncEntities();
            refreshWorkingSet(true);
        }
        attachedPlayer = null;
        cameraMode = CameraMode.FREE;
        freeCamera = pose;
        updateCameraEntity();
        refreshWorkingSet(false);
    }

    public ApplyResult result() {
        return new ApplyResult(
                activeDimension,
                (int) chunkBuildResults.values().stream().filter(ReplayChunkBuilder.ChunkBuildResult::installed).count(),
                replayEntities.size(),
                degradedBlocks,
                failedChunks,
                failedBlockEntities
        );
    }

    public void playSound(ReplayWorldSnapshot.SoundEntry entry) {
        ensureOpen();
        if (!entry.dimensionId().equals(activeDimension)) {
            return;
        }
        DecodedPayload.GameSound sound = entry.sound();
        ResourceLocation id = ResourceLocation.tryParse(sound.soundId());
        if (id == null) {
            return;
        }
        var localSound = BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(null);
        if (localSound == null) {
            return;
        }
        SoundSource source = java.util.Arrays.stream(SoundSource.values())
                .filter(value -> value.getName().equals(sound.source()))
                .findFirst()
                .orElse(SoundSource.MASTER);
        level.playLocalSound(
                sound.x(),
                sound.y(),
                sound.z(),
                localSound,
                source,
                sound.volume(),
                sound.pitch(),
                false
        );
    }

    public void playEntityEffect(ReplayWorldSnapshot.EntityEffectEntry entry) {
        ensureOpen();
        if (!entry.dimensionId().equals(activeDimension)) {
            return;
        }
        Entity target = replayEntities.get(entry.effect().entityId());
        if (target == null) {
            return;
        }
        switch (entry.effect().effect()) {
            case "critical_hit" -> minecraft.particleEngine.createTrackingEmitter(target, ParticleTypes.CRIT);
            case "magic_critical_hit" -> minecraft.particleEngine.createTrackingEmitter(
                    target,
                    ParticleTypes.ENCHANTED_HIT
            );
            default -> {
                // Unknown future effects remain portable and are skipped by older players.
            }
        }
    }

    public void notifySeekStarted() {
        fallbackWalkStates.clear();
        fallbackLivingStates.clear();
        recordedLivingClocks.clear();
        for (ReplayLodAdapterRegistry.SessionHandle session : lodSessions) {
            session.onSeekStarted();
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (ACTIVE_CONTROLLER == this) {
            ACTIVE_CONTROLLER = null;
        }
        teardownLevel();
        connection.close();
        minecraft.options.setCameraType(previousCameraType);
        minecraft.options.fov().set(previousFov);
        minecraft.gameRenderer.resetData();
        for (ReplayLodAdapterRegistry.SessionHandle session : lodSessions) {
            session.close();
        }
    }

    private void rebuildLevel(String dimensionId) {
        if (level != null) {
            teardownLevel();
        }
        activeDimension = dimensionId;
        ReplayWorldSnapshot.DimensionSnapshot dimension = snapshot.dimensions().get(dimensionId);
        if (dimension == null) {
            throw new IllegalArgumentException("Replay dimension is not present: " + dimensionId);
        }
        DecodedPayload.DimensionState environment = dimension.environment().orElse(null);
        Difficulty difficulty = environment == null ? Difficulty.NORMAL : Difficulty.byId(environment.difficultyId());
        ClientLevel.ClientLevelData data = new ClientLevel.ClientLevelData(difficulty, false, false);
        ResourceLocation location = ResourceLocation.tryParse(dimensionId);
        if (location == null) {
            location = ResourceLocation.withDefaultNamespace("overworld");
        }
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, location);
        Holder<DimensionType> dimensionType = dimensionType(dimensionId, dimension);
        level = new ClientLevel(
                connection,
                data,
                key,
                dimensionType,
                VIEW_DISTANCE,
                VIEW_DISTANCE,
                minecraft::getProfiler,
                minecraft.levelRenderer,
                false,
                0L
        );
        ACTIVE_LEVEL = level;
        ((ClientPacketListenerAccessor) connection).dreamingrecall$setLevel(level);
        minecraft.level = level;
        ((MinecraftAccessor) minecraft).dreamingrecall$updateLevelInEngines(level);

        minecraft.gameMode = new MultiPlayerGameMode(minecraft, connection);
        cameraPlayer = new LocalPlayer(
                minecraft,
                level,
                connection,
                new StatsCounter(),
                new ClientRecipeBook(),
                false,
                false
        );
        cameraPlayer.setId(CAMERA_ENTITY_ID);
        cameraPlayer.setUUID(CAMERA_UUID);
        cameraPlayer.setInvisible(true);
        cameraPlayer.setNoGravity(true);
        cameraPlayer.noPhysics = true;
        cameraPlayer.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(1024.0);
        cameraPlayer.input = new KeyboardInput(minecraft.options);
        minecraft.player = cameraPlayer;
        minecraft.gameMode.setLocalMode(GameType.SPECTATOR);
        level.addEntity(cameraPlayer);
        minecraft.setCameraEntity(cameraPlayer);
        minecraft.options.setCameraType(CameraType.FIRST_PERSON);

        replayEntities.clear();
        renderedEntityStates.clear();
        renderedPlayerStates.clear();
        fallbackWalkStates.clear();
        fallbackLivingStates.clear();
        recordedLivingClocks.clear();
        renderedChunks.clear();
        chunkBuildResults.clear();
        degradedBlocks = 0;
        failedChunks = 0;
        failedBlockEntities = 0;
        nextEntityId = 2;
        centerChunkX = Integer.MIN_VALUE;
        centerChunkZ = Integer.MIN_VALUE;
    }

    private void teardownLevel() {
        if (level == null) {
            return;
        }
        if (ACTIVE_LEVEL == level) {
            ACTIVE_LEVEL = null;
        }
        minecraft.setCameraEntity(cameraPlayer);
        NeoForge.EVENT_BUS.post(new LevelEvent.Unload(level));
        ((ClientPacketListenerAccessor) connection).dreamingrecall$setLevel(null);
        minecraft.level = null;
        minecraft.player = null;
        minecraft.cameraEntity = null;
        minecraft.gameMode = null;
        ((MinecraftAccessor) minecraft).dreamingrecall$updateLevelInEngines(null);
        replayEntities.clear();
        renderedChunks.clear();
        chunkBuildResults.clear();
        level = null;
        cameraPlayer = null;
    }

    private ClientPacketListener createConnection() {
        GameProfile profile = new GameProfile(CAMERA_UUID, "DreamingRecallCamera");
        WorldSessionTelemetryManager telemetry = new WorldSessionTelemetryManager(
                TelemetryEventSender.DISABLED,
                false,
                null,
                null
        );
        CommonListenerCookie cookie = new CommonListenerCookie(
                profile,
                telemetry,
                registries,
                FeatureFlags.DEFAULT_FLAGS,
                "DreamingRecall",
                null,
                null,
                Map.of(),
                null,
                false,
                Map.of(),
                ServerLinks.EMPTY,
                ConnectionType.OTHER
        );
        return new ClientPacketListener(
                minecraft,
                new Connection(PacketFlow.CLIENTBOUND),
                cookie
        );
    }

    private Holder<DimensionType> dimensionType(
            String dimensionId,
            ReplayWorldSnapshot.DimensionSnapshot dimension
    ) {
        ResourceKey<DimensionType> templateKey = switch (dimensionId) {
            case "minecraft:the_nether" -> BuiltinDimensionTypes.NETHER;
            case "minecraft:the_end" -> BuiltinDimensionTypes.END;
            default -> BuiltinDimensionTypes.OVERWORLD;
        };
        DimensionType template = registries.registryOrThrow(Registries.DIMENSION_TYPE)
                .getHolderOrThrow(templateKey)
                .value();
        int minSection = Integer.MAX_VALUE;
        int maxSection = Integer.MIN_VALUE;
        for (ReplayWorldSnapshot.ChunkSnapshot chunk : dimension.chunks().values()) {
            if (chunk.baseline().isEmpty() || !chunk.baseline().get().available()) {
                continue;
            }
            for (DecodedPayload.ChunkSection section : chunk.baseline().get().sections()) {
                minSection = Math.min(minSection, section.sectionY());
                maxSection = Math.max(maxSection, section.sectionY());
            }
        }
        int minY = minSection == Integer.MAX_VALUE ? template.minY() : minSection * 16;
        int height = maxSection == Integer.MIN_VALUE ? template.height() : (maxSection - minSection + 1) * 16;
        DimensionType replayType = new DimensionType(
                template.fixedTime(),
                template.hasSkyLight(),
                template.hasCeiling(),
                template.ultraWarm(),
                template.natural(),
                template.coordinateScale(),
                template.bedWorks(),
                template.respawnAnchorWorks(),
                minY,
                height,
                Math.min(template.logicalHeight(), height),
                template.infiniburn(),
                template.effectsLocation(),
                template.ambientLight(),
                template.monsterSettings()
        );
        return Holder.direct(replayType);
    }

    private void applyEnvironment() {
        ReplayWorldSnapshot.DimensionSnapshot dimension = snapshot.dimensions().get(activeDimension);
        if (dimension == null || dimension.environment().isEmpty()) {
            return;
        }
        DecodedPayload.DimensionState environment = dimension.environment().get();
        level.setGameTime(environment.gameTime());
        level.setDayTime(environment.dayTime());
        level.getLevelData().setRaining(environment.rainLevel() > 0.0F);
        level.setRainLevel(environment.rainLevel());
        level.setThunderLevel(environment.thunderLevel());
        level.getWorldBorder().setCenter(environment.borderCenterX(), environment.borderCenterZ());
        level.getWorldBorder().setSize(environment.borderSize());
    }

    private void notifyLodSnapshot(ReplayWorldSnapshot next) {
        for (ReplayLodAdapterRegistry.SessionHandle session : lodSessions) {
            session.onSnapshot(next);
        }
    }

    private void syncEntities() {
        ReplayWorldSnapshot.DimensionSnapshot dimension = snapshot.dimensions().get(activeDimension);
        if (dimension == null) {
            return;
        }
        Set<UUID> desired = new HashSet<>();
        desired.addAll(dimension.entities().keySet());
        desired.addAll(dimension.players().keySet());
        replayEntities.entrySet().removeIf(entry -> {
            if (desired.contains(entry.getKey())) {
                return false;
            }
            level.removeEntity(entry.getValue().getId(), Entity.RemovalReason.DISCARDED);
            renderedEntityStates.remove(entry.getKey());
            renderedPlayerStates.remove(entry.getKey());
            fallbackLivingStates.remove(entry.getKey());
            recordedLivingClocks.remove(entry.getKey());
            return true;
        });

        dimension.entities().forEach((uuid, state) -> {
            if (dimension.players().containsKey(uuid)) {
                return;
            }
            Entity entity = replayEntities.get(uuid);
            if (entity == null || entity instanceof RemotePlayer) {
                if (entity != null) {
                    level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
                }
                entity = ReplayEntityFactory.createEntity(level, state);
                entity.setId(nextEntityId++);
                level.addEntity(entity);
                replayEntities.put(uuid, entity);
            }
            if (!state.equals(renderedEntityStates.get(uuid))) {
                ReplayEntityFactory.updateEntity(entity, state);
                renderedEntityStates.put(uuid, state);
            }
        });

        dimension.players().forEach((uuid, recordedState) -> {
            DecodedPayload.PlayerState state = playbackPlayerState(uuid, recordedState);
            Entity current = replayEntities.get(uuid);
            RemotePlayer player;
            if (current instanceof RemotePlayer remote) {
                player = remote;
            } else {
                if (current != null) {
                    level.removeEntity(current.getId(), Entity.RemovalReason.DISCARDED);
                }
                player = ReplayEntityFactory.createPlayer(level, state);
                player.setId(nextEntityId++);
                level.addEntity(player);
                replayEntities.put(uuid, player);
            }
            if (!state.equals(renderedPlayerStates.get(uuid))) {
                ReplayEntityFactory.updatePlayer(player, state, renderedPlayerStates.get(uuid));
                renderedPlayerStates.put(uuid, state);
            }
        });
    }

    private void syncInterpolatedPlayers() {
        ReplayWorldSnapshot.DimensionSnapshot dimension = snapshot.dimensions().get(activeDimension);
        if (dimension == null) {
            return;
        }
        dimension.players().forEach((uuid, recordedState) -> {
            Entity current = replayEntities.get(uuid);
            if (!(current instanceof RemotePlayer player)) {
                return;
            }
            DecodedPayload.PlayerState state = playbackPlayerState(uuid, recordedState);
            if (!state.equals(renderedPlayerStates.get(uuid))) {
                ReplayEntityFactory.updatePlayer(player, state, renderedPlayerStates.get(uuid));
                renderedPlayerStates.put(uuid, state);
            }
        });
    }

    private void prepareAnimations(float partialTick) {
        int replayTick = (int) Math.min(Integer.MAX_VALUE, snapshot.archiveNanos() / TICK_NANOS);
        replayEntities.values().forEach(entity -> entity.tickCount = replayTick);
        if (cameraPlayer != null) {
            cameraPlayer.tickCount = replayTick;
        }

        renderedPlayerStates.forEach((uuid, state) -> {
            Entity entity = replayEntities.get(uuid);
            if (entity instanceof RemotePlayer player) {
                ReplayEntityFactory.preparePlayerAnimationForRender(player, state, partialTick);
            }
        });
        renderedEntityStates.forEach((uuid, state) -> prepareLivingAnimation(
                uuid,
                replayEntities.get(uuid),
                state,
                partialTick
        ));
    }

    private void prepareLivingAnimation(
            UUID uuid,
            Entity entity,
            DecodedPayload.EntityState state,
            float partialTick
    ) {
        if (!(entity instanceof LivingEntity living)) {
            fallbackLivingStates.remove(uuid);
            recordedLivingClocks.remove(uuid);
            return;
        }
        DecodedPayload.LivingDetails details = state.details()
                .flatMap(DecodedPayload.EntityDetails::living)
                .orElse(null);
        if (details == null) {
            fallbackLivingStates.remove(uuid);
            recordedLivingClocks.remove(uuid);
            return;
        }
        DecodedPayload.LivingAnimation recorded = details.animation().orElse(null);
        if (recorded != null) {
            fallbackLivingStates.remove(uuid);
            RecordedLivingClock clock = recordedLivingClocks.computeIfAbsent(
                    uuid,
                    ignored -> new RecordedLivingClock()
            );
            ReplayEntityFactory.applyLivingAnimationForRender(
                    living,
                    clock.animationAt(recorded, snapshot.archiveNanos()),
                    partialTick
            );
            return;
        }

        recordedLivingClocks.remove(uuid);
        FallbackLivingState fallback = fallbackLivingStates.computeIfAbsent(
                uuid,
                ignored -> new FallbackLivingState(snapshot.archiveNanos(), details.health())
        );
        fallback.advance(snapshot.archiveNanos(), state.transform(), details.health());
        ReplayEntityFactory.applyFallbackLivingAnimation(
                living,
                fallback.position,
                fallback.speed,
                fallback.hurtTime(snapshot.archiveNanos()),
                fallback.deathTime(snapshot.archiveNanos()),
                partialTick
        );
    }

    private DecodedPayload.PlayerState playbackPlayerState(
            UUID playerId,
            DecodedPayload.PlayerState recordedState
    ) {
        DecodedPayload.PlayerState state = playbackFrame == null
                ? recordedState
                : ReplayMotionInterpolator.playerAt(playbackFrame, playerId, recordedState);
        if (state.animation().isPresent()
                && (playbackFrame == null
                || ReplayMotionInterpolator.hasClientPlayerVisual(playbackFrame, playerId))) {
            fallbackWalkStates.remove(playerId);
            return state;
        }
        return withFallbackWalkAnimation(playerId, state);
    }

    private DecodedPayload.PlayerState withFallbackWalkAnimation(
            UUID playerId,
            DecodedPayload.PlayerState state
    ) {
        long archiveNanos = snapshot.archiveNanos();
        FallbackWalkState fallback = fallbackWalkStates.computeIfAbsent(
                playerId,
                ignored -> new FallbackWalkState(archiveNanos)
        );
        double elapsedTicks = Math.max(0.0, Math.min(
                20.0,
                (archiveNanos - fallback.lastArchiveNanos) / 50_000_000.0
        ));
        double horizontalVelocity = Math.hypot(
                state.transform().velocityX(),
                state.transform().velocityZ()
        );
        float targetSpeed = (float) Math.min(1.0, horizontalVelocity * 4.0);
        double smoothing = 1.0 - Math.pow(0.6, elapsedTicks);
        fallback.speed += (targetSpeed - fallback.speed) * (float) smoothing;
        fallback.position += fallback.speed * (float) elapsedTicks;
        fallback.lastArchiveNanos = archiveNanos;
        DecodedPayload.PlayerAnimation recorded = state.animation().orElse(null);
        DecodedPayload.PlayerAnimation animation = new DecodedPayload.PlayerAnimation(
                fallback.position,
                fallback.speed,
                recorded == null ? 0.0F : recorded.attackProgress(),
                recorded != null && recorded.swinging(),
                recorded == null ? 0 : recorded.swingTime(),
                recorded == null ? "MAIN_HAND" : recorded.swingingArm(),
                recorded != null && recorded.usingItem(),
                recorded == null ? "MAIN_HAND" : recorded.usedItemHand(),
                recorded == null ? 0 : recorded.useItemRemainingTicks(),
                state.transform().pose().equalsIgnoreCase("swimming")
                        ? 1.0F
                        : recorded == null ? 0.0F : recorded.swimAmount(),
                state.transform().pose().equalsIgnoreCase("fall_flying")
                        ? Math.max(1, recorded == null ? 0 : recorded.fallFlyingTicks())
                        : recorded == null ? 0 : recorded.fallFlyingTicks()
        );
        return new DecodedPayload.PlayerState(
                state.uuid(),
                state.name(),
                state.transform(),
                state.eyeX(),
                state.eyeY(),
                state.eyeZ(),
                state.headYaw(),
                state.bodyYaw(),
                state.health(),
                state.absorption(),
                state.foodLevel(),
                state.selectedSlot(),
                state.gameMode(),
                state.equipment(),
                Optional.of(animation)
        );
    }

    private void refreshWorkingSet(boolean force) {
        if (level == null || freeCamera == null) {
            return;
        }
        Vec3 camera = cameraPosition();
        int chunkX = Mth.floor(camera.x()) >> 4;
        int chunkZ = Mth.floor(camera.z()) >> 4;
        boolean movedCenter = chunkX != centerChunkX || chunkZ != centerChunkZ;
        if (movedCenter) {
            centerChunkX = chunkX;
            centerChunkZ = chunkZ;
            level.getChunkSource().updateViewCenter(chunkX, chunkZ);
        }

        ReplayWorldSnapshot.DimensionSnapshot dimension = snapshot.dimensions().get(activeDimension);
        if (dimension == null) {
            return;
        }
        LinkedHashMap<ReplayWorldSnapshot.ChunkKey, ReplayWorldSnapshot.ChunkSnapshot> desired = new LinkedHashMap<>();
        dimension.chunks().forEach((key, chunk) -> {
            if (Math.abs(key.x() - chunkX) <= VIEW_DISTANCE + 2
                    && Math.abs(key.z() - chunkZ) <= VIEW_DISTANCE + 2) {
                desired.put(key, chunk);
            }
        });
        renderedChunks.keySet().removeIf(key -> {
            if (desired.containsKey(key)) {
                return false;
            }
            level.getChunkSource().drop(new net.minecraft.world.level.ChunkPos(key.x(), key.z()));
            removeChunkResult(key);
            return true;
        });
        desired.forEach((key, chunk) -> {
            ReplayWorldSnapshot.ChunkSnapshot previous = renderedChunks.get(key);
            if (!force && chunk.equals(previous)) {
                return;
            }
            ReplayChunkBuilder.ChunkBuildResult built = ReplayChunkBuilder.install(level, key, chunk);
            removeChunkResult(key);
            renderedChunks.put(key, chunk);
            chunkBuildResults.put(key, built);
            if (built.installed()) {
                degradedBlocks += built.degradedBlocks();
                failedBlockEntities += built.blockEntityFailures();
            } else {
                failedChunks++;
            }
        });
        if (force) {
            minecraft.levelRenderer.allChanged();
        }
    }

    private void removeChunkResult(ReplayWorldSnapshot.ChunkKey key) {
        ReplayChunkBuilder.ChunkBuildResult previous = chunkBuildResults.remove(key);
        if (previous == null) {
            return;
        }
        if (previous.installed()) {
            degradedBlocks = Math.max(0, degradedBlocks - previous.degradedBlocks());
            failedBlockEntities = Math.max(0, failedBlockEntities - previous.blockEntityFailures());
        } else {
            failedChunks = Math.max(0, failedChunks - 1);
        }
    }

    private void updateCameraEntity() {
        if (level == null || cameraPlayer == null) {
            return;
        }
        if (cameraMode == CameraMode.FREE) {
            cameraPlayer.setInvisible(true);
            setCameraPlayerGameMode(GameType.SPECTATOR);
            cameraPlayer.setPose(Pose.STANDING);
            cameraPlayer.setPos(
                    freeCamera.x(),
                    freeCamera.y() - cameraPlayer.getEyeHeight(),
                    freeCamera.z()
            );
            cameraPlayer.setYRot(freeCamera.yaw());
            cameraPlayer.setXRot(freeCamera.pitch());
            cameraPlayer.setOldPosAndRot();
            minecraft.setCameraEntity(cameraPlayer);
            minecraft.options.setCameraType(CameraType.FIRST_PERSON);
            applyFov(freeCamera.fov());
            return;
        }
        Entity target = attachedPlayer == null ? null : replayEntities.get(attachedPlayer);
        if (target == null) {
            captureRenderedCamera();
            cameraMode = CameraMode.FREE;
            updateCameraEntity();
            return;
        }
        if (cameraMode == CameraMode.FIRST_PERSON) {
            attachedCameraSample().ifPresent(sample -> {
                target.setPos(sample.x(), sample.y() - target.getEyeHeight(), sample.z());
                target.setYRot(sample.yaw());
                target.setXRot(sample.pitch());
                target.setOldPosAndRot();
            });
            DecodedPayload.PlayerState state = renderedPlayerStates.get(attachedPlayer);
            if (target instanceof RemotePlayer player && state != null) {
                ReplayEntityFactory.updateFirstPersonProxy(cameraPlayer, player, state);
                setCameraPlayerGameMode(GameType.byName(state.gameMode(), GameType.SURVIVAL));
            } else {
                cameraPlayer.setInvisible(true);
                setCameraPlayerGameMode(GameType.SURVIVAL);
            }
        } else {
            cameraPlayer.setInvisible(true);
            setCameraPlayerGameMode(GameType.SPECTATOR);
        }
        minecraft.setCameraEntity(cameraMode == CameraMode.FIRST_PERSON ? cameraPlayer : target);
        minecraft.options.setCameraType(
                cameraMode == CameraMode.FIRST_PERSON ? CameraType.FIRST_PERSON : CameraType.THIRD_PERSON_BACK
        );
        applyFov(currentFov());
    }

    private void captureRenderedCamera() {
        Camera rendered = minecraft.gameRenderer.getMainCamera();
        if (rendered.isInitialized() && rendered.getEntity() != null) {
            Vec3 position = rendered.getPosition();
            freeCamera = new CameraPose(
                    activeDimension,
                    position.x(),
                    position.y(),
                    position.z(),
                    rendered.getYRot(),
                    rendered.getXRot(),
                    rendered.getRoll(),
                    currentFov()
            );
            return;
        }
        freeCamera = cameraPose();
    }

    private void setCameraPlayerGameMode(GameType mode) {
        if (minecraft.gameMode.getPlayerMode() != mode) {
            minecraft.gameMode.setLocalMode(mode);
        }
    }

    private float currentFov() {
        return attachedCameraSample().map(DecodedPayload.CameraSample::fov)
                .orElseGet(() -> freeCamera == null ? previousFov : freeCamera.fov());
    }

    private void applyFov(float fov) {
        minecraft.options.fov().set(Mth.clamp(Math.round(fov), 30, 110));
    }

    private Vec3 cameraPosition() {
        if (cameraMode != CameraMode.FREE && attachedPlayer != null) {
            DecodedPayload.CameraSample sample = attachedCameraSample().orElse(null);
            if (sample != null) {
                return new Vec3(sample.x(), sample.y(), sample.z());
            }
            Entity target = replayEntities.get(attachedPlayer);
            if (target != null) {
                return target.getEyePosition();
            }
        }
        return new Vec3(freeCamera.x(), freeCamera.y(), freeCamera.z());
    }

    private Optional<DecodedPayload.CameraSample> attachedCameraSample() {
        if (attachedPlayer == null || snapshot == null || activeDimension == null) {
            return Optional.empty();
        }
        if (playbackFrame != null) {
            ReplayPlaybackFrame.TimedCameraSample current = playbackFrame.currentCameraSamples().get(attachedPlayer);
            if (current != null && activeDimension.equals(current.dimensionId())) {
                return ReplayMotionInterpolator.cameraAt(playbackFrame, attachedPlayer);
            }
        }
        ReplayWorldSnapshot.DimensionSnapshot dimension = snapshot.dimensions().get(activeDimension);
        return dimension == null
                ? Optional.empty()
                : Optional.ofNullable(dimension.cameraSamples().get(attachedPlayer));
    }

    private static String chooseInitialDimension(ReplayWorldSnapshot snapshot) {
        if (snapshot.dimensions().containsKey("minecraft:overworld")) {
            return "minecraft:overworld";
        }
        return snapshot.dimensions().keySet().stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Replay has no dimensions at this time"));
    }

    private static CameraPose seedCamera(ReplayWorldSnapshot snapshot, String dimensionId) {
        ReplayWorldSnapshot.DimensionSnapshot dimension = snapshot.dimensions().get(dimensionId);
        if (dimension == null) {
            return new CameraPose(dimensionId, 0.0, 80.0, 0.0, 0.0F, 0.0F, 0.0F, 70.0F);
        }
        Optional<DecodedPayload.PlayerState> player = dimension.players().values().stream().findFirst();
        if (player.isPresent()) {
            DecodedPayload.PlayerState value = player.get();
            return new CameraPose(
                    dimensionId,
                    value.eyeX(),
                    value.eyeY(),
                    value.eyeZ(),
                    value.headYaw(),
                    value.transform().pitch(),
                    0.0F,
                    70.0F
            );
        }
        Optional<DecodedPayload.EntityState> entity = dimension.entities().values().stream().findFirst();
        if (entity.isPresent()) {
            DecodedPayload.Transform transform = entity.get().transform();
            return new CameraPose(
                    dimensionId,
                    transform.x(),
                    transform.y() + 1.6,
                    transform.z(),
                    transform.yaw(),
                    transform.pitch(),
                    0.0F,
                    70.0F
            );
        }
        Optional<ReplayWorldSnapshot.ChunkKey> chunk = dimension.chunks().keySet().stream().findFirst();
        return chunk.map(key -> new CameraPose(
                dimensionId,
                key.x() * 16.0 + 8.0,
                80.0,
                key.z() * 16.0 + 8.0,
                0.0F,
                20.0F,
                0.0F,
                70.0F
        )).orElseGet(() -> new CameraPose(dimensionId, 0.0, 80.0, 0.0, 0.0F, 20.0F, 0.0F, 70.0F));
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Replay world is closed");
        }
    }

    public enum CameraMode {
        FREE,
        PLAYER,
        FIRST_PERSON
    }

    private static final class FallbackWalkState {
        private long lastArchiveNanos;
        private float position;
        private float speed;

        private FallbackWalkState(long lastArchiveNanos) {
            this.lastArchiveNanos = lastArchiveNanos;
        }
    }

    private static final class FallbackLivingState {
        private long lastArchiveNanos;
        private long hurtStartedNanos = Long.MIN_VALUE;
        private long deathStartedNanos = Long.MIN_VALUE;
        private float lastHealth;
        private float position;
        private float speed;

        private FallbackLivingState(long archiveNanos, float health) {
            lastArchiveNanos = archiveNanos;
            lastHealth = health;
            if (health <= 0.0F) {
                deathStartedNanos = archiveNanos;
            }
        }

        private void advance(long archiveNanos, DecodedPayload.Transform transform, float health) {
            double elapsedTicks = Math.max(0.0, Math.min(
                    20.0,
                    (archiveNanos - lastArchiveNanos) / (double) TICK_NANOS
            ));
            float targetSpeed = (float) Math.min(
                    1.0,
                    Math.hypot(transform.velocityX(), transform.velocityZ()) * 4.0
            );
            double smoothing = 1.0 - Math.pow(0.6, elapsedTicks);
            speed += (targetSpeed - speed) * (float) smoothing;
            position += speed * (float) elapsedTicks;
            lastArchiveNanos = archiveNanos;

            if (health < lastHealth) {
                hurtStartedNanos = archiveNanos;
                if (health <= 0.0F && lastHealth > 0.0F) {
                    deathStartedNanos = archiveNanos;
                }
            } else if (health > 0.0F) {
                deathStartedNanos = Long.MIN_VALUE;
            }
            lastHealth = health;
        }

        private int hurtTime(long archiveNanos) {
            if (hurtStartedNanos == Long.MIN_VALUE) {
                return 0;
            }
            int elapsed = (int) Math.max(0L, (archiveNanos - hurtStartedNanos) / TICK_NANOS);
            return Math.max(0, 10 - elapsed);
        }

        private int deathTime(long archiveNanos) {
            if (deathStartedNanos == Long.MIN_VALUE) {
                return 0;
            }
            return (int) Math.min(20L, Math.max(0L, (archiveNanos - deathStartedNanos) / TICK_NANOS));
        }
    }

    private static final class RecordedLivingClock {
        private DecodedPayload.LivingAnimation sample;
        private long sampleArchiveNanos;

        private DecodedPayload.LivingAnimation animationAt(
                DecodedPayload.LivingAnimation recorded,
                long archiveNanos
        ) {
            if (!recorded.equals(sample)) {
                sample = recorded;
                sampleArchiveNanos = archiveNanos;
            }
            double elapsedTicks = Math.max(0.0, Math.min(
                    20.0,
                    (archiveNanos - sampleArchiveNanos) / (double) TICK_NANOS
            ));
            int wholeTicks = (int) elapsedTicks;
            return new DecodedPayload.LivingAnimation(
                    Math.max(0, recorded.hurtTime() - wholeTicks),
                    recorded.deathTime() <= 0
                            ? 0
                            : Math.min(20, recorded.deathTime() + wholeTicks),
                    recorded.walkPosition() + recorded.walkSpeed() * (float) elapsedTicks,
                    recorded.walkSpeed(),
                    recorded.attackProgress(),
                    recorded.swinging(),
                    recorded.swingTime()
            );
        }
    }

    public record PlayerTarget(UUID uuid, String name, String dimensionId) {
    }

    public record ApplyResult(
            String dimensionId,
            int loadedChunks,
            int renderedEntities,
            int degradedBlocks,
            int failedChunks,
            int failedBlockEntities
    ) {
    }
}
