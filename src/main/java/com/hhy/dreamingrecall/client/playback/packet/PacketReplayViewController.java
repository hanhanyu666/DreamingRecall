package com.hhy.dreamingrecall.client.playback.packet;

import com.hhy.dreamingrecall.client.playback.ReplayViewController;
import com.hhy.dreamingrecall.client.playback.ReplayEntityFactory;
import com.hhy.dreamingrecall.client.playback.ReplayWorldController;
import com.hhy.dreamingrecall.client.playback.ReplayClock;
import com.hhy.dreamingrecall.client.playback.FreeCameraMotion;
import com.hhy.dreamingrecall.director.CameraPose;
import com.hhy.dreamingrecall.playback.decode.DecodedPayload;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PacketReplayViewController implements ReplayViewController {
    private static final int CAMERA_ENTITY_ID = 1_900_000_001;
    private static final UUID CAMERA_UUID = UUID.nameUUIDFromBytes(
            "DreamingRecall packet camera".getBytes(StandardCharsets.UTF_8)
    );
    private static volatile PacketReplayViewController ACTIVE;

    private final Minecraft minecraft;
    private final CameraType previousCameraType;
    private final int previousFov;
    private ClientLevel level;
    private RemotePlayer freeCameraEntity;
    private CameraPose freeCamera;
    private ReplayWorldController.CameraMode cameraMode = ReplayWorldController.CameraMode.FREE;
    private UUID attachedPlayer;
    private DecodedPayload.CameraSample latestCamera;
    private boolean closed;

    PacketReplayViewController(Minecraft minecraft) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.previousCameraType = minecraft.options.getCameraType();
        this.previousFov = minecraft.options.fov().get();
        this.level = Objects.requireNonNull(minecraft.level, "replay level");
        this.freeCamera = seedCamera();
        createFreeCameraEntity();
        updateCameraEntity();
        ReplayClock.activate(level);
        ACTIVE = this;
    }

    public static boolean isReplayLevel(ClientLevel candidate) {
        PacketReplayViewController active = ACTIVE;
        return active != null && !active.closed && active.level == candidate;
    }

    public static boolean shouldSuppressReplayEntityRender(Entity candidate) {
        PacketReplayViewController active = ACTIVE;
        if (active == null || active.closed) {
            return false;
        }
        if (candidate == active.freeCameraEntity) {
            return true;
        }
        return active.cameraMode == ReplayWorldController.CameraMode.FIRST_PERSON
                && active.attachedPlayer != null
                && candidate.getUUID().equals(active.attachedPlayer);
    }

    public static boolean shouldSuppressFirstPersonHand() {
        PacketReplayViewController active = ACTIVE;
        return active != null
                && !active.closed
                && active.cameraMode != ReplayWorldController.CameraMode.FIRST_PERSON;
    }

    void refreshAfterPackets() {
        if (closed || minecraft.level == null) {
            return;
        }
        if (minecraft.level != level) {
            if (freeCameraEntity != null && freeCameraEntity.level() == level) {
                level.removeEntity(freeCameraEntity.getId(), Entity.RemovalReason.DISCARDED);
            }
            level = minecraft.level;
            if (!activeDimension().equals(freeCamera.dimensionId())) {
                freeCamera = seedCamera();
            }
            createFreeCameraEntity();
            ReplayClock.activate(level);
        }
        updateCameraEntity();
    }

    void applyTelemetry(
            DecodedPayload.PlayerVisualSample playerVisual,
            DecodedPayload.CameraSample camera
    ) {
        if (closed) {
            return;
        }
        if (playerVisual != null
                && minecraft.player != null
                && minecraft.player.getUUID().equals(playerVisual.playerId())) {
            ReplayEntityFactory.updateClientPlayer(minecraft.player, playerVisual.playerSample());
        }
        latestCamera = camera;
        updateCameraEntity();
    }

    @Override
    public ReplayWorldController.CameraMode cameraMode() {
        return cameraMode;
    }

    @Override
    public void setCameraMode(ReplayWorldController.CameraMode mode) {
        ensureOpen();
        ReplayWorldController.CameraMode requested = Objects.requireNonNull(mode, "mode");
        if (requested == ReplayWorldController.CameraMode.FREE
                && cameraMode != ReplayWorldController.CameraMode.FREE) {
            boolean leavingFirstPerson = cameraMode == ReplayWorldController.CameraMode.FIRST_PERSON;
            captureRenderedCamera();
            if (leavingFirstPerson) {
                freeCamera = moveBehind(freeCamera, 0.75);
            }
        }
        cameraMode = requested;
        if (requested != ReplayWorldController.CameraMode.FREE && attachedPlayer == null) {
            attachedPlayer = playerTargets().stream().findFirst().map(ReplayWorldController.PlayerTarget::uuid).orElse(null);
        }
        updateCameraEntity();
    }

    @Override
    public void attachPlayer(UUID playerId, boolean firstPerson) {
        ensureOpen();
        Entity target = player(playerId);
        if (target == null) {
            throw new IllegalArgumentException("Replay player is not present: " + playerId);
        }
        attachedPlayer = playerId;
        cameraMode = firstPerson
                ? ReplayWorldController.CameraMode.FIRST_PERSON
                : ReplayWorldController.CameraMode.PLAYER;
        updateCameraEntity();
    }

    @Override
    public List<ReplayWorldController.PlayerTarget> playerTargets() {
        if (level == null) {
            return List.of();
        }
        ArrayList<ReplayWorldController.PlayerTarget> targets = new ArrayList<>();
        for (Player player : level.players()) {
            if (!player.getUUID().equals(CAMERA_UUID)) {
                targets.add(new ReplayWorldController.PlayerTarget(
                        player.getUUID(),
                        player.getGameProfile().getName(),
                        activeDimension()
                ));
            }
        }
        return List.copyOf(targets);
    }

    @Override
    public Optional<ReplayWorldController.PlayerTarget> attachedPlayerTarget() {
        if (cameraMode == ReplayWorldController.CameraMode.FREE || attachedPlayer == null) {
            return Optional.empty();
        }
        return playerTargets().stream().filter(target -> target.uuid().equals(attachedPlayer)).findFirst();
    }

    @Override
    public List<String> dimensions() {
        return level == null ? List.of() : List.of(activeDimension());
    }

    @Override
    public String activeDimension() {
        return level.dimension().location().toString();
    }

    @Override
    public void selectDimension(String dimensionId) {
        if (!activeDimension().equals(dimensionId)) {
            throw new IllegalArgumentException("This client track has no simultaneous view of " + dimensionId);
        }
    }

    @Override
    public void moveFreeCamera(
            double forward,
            double strafe,
            double vertical,
            boolean fast,
            double elapsedSeconds
    ) {
        ensureOpen();
        if (cameraMode != ReplayWorldController.CameraMode.FREE) {
            return;
        }
        double speed = (fast ? 40.0 : 7.0) * Math.max(0.0, Math.min(0.25, elapsedSeconds));
        FreeCameraMotion.Delta delta = FreeCameraMotion.horizontalDelta(
                minecraft.gameRenderer.getMainCamera(),
                freeCamera.yaw(),
                forward,
                strafe,
                speed
        );
        freeCamera = new CameraPose(
                activeDimension(),
                freeCamera.x() + delta.x(),
                freeCamera.y() + vertical * speed,
                freeCamera.z() + delta.z(),
                freeCamera.yaw(),
                freeCamera.pitch(),
                freeCamera.roll(),
                freeCamera.fov()
        );
        updateCameraEntity();
    }

    @Override
    public void turnFreeCamera(float deltaYaw, float deltaPitch) {
        ensureOpen();
        if (cameraMode != ReplayWorldController.CameraMode.FREE) {
            return;
        }
        freeCamera = new CameraPose(
                activeDimension(),
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

    @Override
    public CameraPose cameraPose() {
        if (cameraMode == ReplayWorldController.CameraMode.FREE || attachedPlayer == null) {
            return freeCamera;
        }
        Entity target = player(attachedPlayer);
        if (target == null) {
            return freeCamera;
        }
        DecodedPayload.CameraSample camera = attachedCameraSample();
        if (cameraMode == ReplayWorldController.CameraMode.FIRST_PERSON && camera != null) {
            return new CameraPose(
                    activeDimension(),
                    camera.x(),
                    camera.y(),
                    camera.z(),
                    camera.yaw(),
                    camera.pitch(),
                    camera.roll(),
                    camera.fov()
            );
        }
        Vec3 eye = target.getEyePosition();
        return new CameraPose(
                activeDimension(),
                eye.x(),
                eye.y(),
                eye.z(),
                target.getYRot(),
                target.getXRot(),
                0.0F,
                minecraft.options.fov().get()
        );
    }

    @Override
    public void applyDirectorPose(CameraPose pose) {
        ensureOpen();
        if (!activeDimension().equals(pose.dimensionId())) {
            return;
        }
        attachedPlayer = null;
        cameraMode = ReplayWorldController.CameraMode.FREE;
        freeCamera = pose;
        updateCameraEntity();
    }

    @Override
    public void notifySeekStarted() {
    }

    private CameraPose seedCamera() {
        Entity target = minecraft.player;
        Vec3 eye = target == null ? new Vec3(0.0, 80.0, 0.0) : target.getEyePosition();
        CameraPose seeded = new CameraPose(
                activeDimension(),
                eye.x(),
                eye.y(),
                eye.z(),
                target == null ? 0.0F : target.getYRot(),
                target == null ? 0.0F : target.getXRot(),
                0.0F,
                minecraft.options.fov().get()
        );
        return target == null ? seeded : moveBehind(seeded, 3.0);
    }

    private static CameraPose moveBehind(CameraPose pose, double distance) {
        double yaw = Math.toRadians(pose.yaw());
        return new CameraPose(
                pose.dimensionId(),
                pose.x() + Math.sin(yaw) * distance,
                pose.y(),
                pose.z() - Math.cos(yaw) * distance,
                pose.yaw(),
                pose.pitch(),
                pose.roll(),
                pose.fov()
        );
    }

    private void createFreeCameraEntity() {
        freeCameraEntity = new RemotePlayer(level, new GameProfile(CAMERA_UUID, "DreamingRecallCamera"));
        freeCameraEntity.setId(CAMERA_ENTITY_ID);
        freeCameraEntity.setInvisible(true);
        freeCameraEntity.setNoGravity(true);
        freeCameraEntity.noPhysics = true;
        level.addEntity(freeCameraEntity);
    }

    private void updateCameraEntity() {
        if (closed || level == null || freeCameraEntity == null) {
            return;
        }
        if (cameraMode == ReplayWorldController.CameraMode.FREE) {
            freeCameraEntity.setPos(
                    freeCamera.x(),
                    freeCamera.y() - freeCameraEntity.getEyeHeight(),
                    freeCamera.z()
            );
            freeCameraEntity.setYRot(freeCamera.yaw());
            freeCameraEntity.setXRot(freeCamera.pitch());
            freeCameraEntity.setOldPosAndRot();
            minecraft.setCameraEntity(freeCameraEntity);
            minecraft.options.setCameraType(CameraType.FIRST_PERSON);
            minecraft.options.fov().set(Mth.clamp(Math.round(freeCamera.fov()), 30, 110));
            return;
        }
        Entity target = attachedPlayer == null ? null : player(attachedPlayer);
        if (target == null) {
            captureRenderedCamera();
            cameraMode = ReplayWorldController.CameraMode.FREE;
            attachedPlayer = null;
            updateCameraEntity();
            return;
        }
        DecodedPayload.CameraSample camera = attachedCameraSample();
        if (cameraMode == ReplayWorldController.CameraMode.FIRST_PERSON && camera != null) {
            target.setPos(camera.x(), camera.y() - target.getEyeHeight(), camera.z());
            target.setYRot(camera.yaw());
            target.setXRot(camera.pitch());
            target.setYHeadRot(camera.yaw());
            target.setOldPosAndRot();
            minecraft.options.fov().set(Mth.clamp(Math.round(camera.fov()), 30, 110));
        }
        minecraft.setCameraEntity(target);
        minecraft.options.setCameraType(cameraMode == ReplayWorldController.CameraMode.FIRST_PERSON
                ? CameraType.FIRST_PERSON
                : CameraType.THIRD_PERSON_BACK);
    }

    private DecodedPayload.CameraSample attachedCameraSample() {
        return latestCamera != null
                && attachedPlayer != null
                && attachedPlayer.equals(latestCamera.playerId())
                ? latestCamera
                : null;
    }

    private Entity player(UUID playerId) {
        if (minecraft.player != null && minecraft.player.getUUID().equals(playerId)) {
            return minecraft.player;
        }
        for (Player player : level.players()) {
            if (player.getUUID().equals(playerId)) {
                return player;
            }
        }
        return null;
    }

    private void captureRenderedCamera() {
        Camera rendered = minecraft.gameRenderer.getMainCamera();
        if (!rendered.isInitialized() || rendered.getEntity() == null) {
            return;
        }
        Vec3 position = rendered.getPosition();
        freeCamera = new CameraPose(
                activeDimension(),
                position.x(),
                position.y(),
                position.z(),
                rendered.getYRot(),
                rendered.getXRot(),
                rendered.getRoll(),
                minecraft.options.fov().get()
        );
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Replay view is closed");
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (ACTIVE == this) {
            ACTIVE = null;
        }
        if (level != null && freeCameraEntity != null && freeCameraEntity.level() == level) {
            level.removeEntity(freeCameraEntity.getId(), Entity.RemovalReason.DISCARDED);
        }
        ReplayClock.deactivate(level);
        minecraft.options.setCameraType(previousCameraType);
        minecraft.options.fov().set(previousFov);
        freeCameraEntity = null;
    }
}
