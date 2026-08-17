package com.hhy.dreamingrecall.client;

import com.hhy.dreamingrecall.DreamingRecall;
import com.hhy.dreamingrecall.client.screen.ReplayLibraryScreen;
import com.hhy.dreamingrecall.client.screen.ReplayTimelineScreen;
import com.hhy.dreamingrecall.client.playback.ReplayWorldController;
import com.hhy.dreamingrecall.client.playback.packet.PacketReplayViewController;
import com.hhy.dreamingrecall.client.playback.packet.ReplayPacketDispatchContext;
import com.hhy.dreamingrecall.client.recording.ClientRecordingManager;
import com.hhy.dreamingrecall.capture.CaptureBridge;
import com.hhy.dreamingrecall.config.DreamingRecallClientConfig;
import com.hhy.dreamingrecall.network.CameraSamplePayload;
import com.hhy.dreamingrecall.network.PlayerVisualSamplePayload;
import com.hhy.dreamingrecall.network.StartRecordingRequestPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.DirectJoinServerScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

@EventBusSubscriber(modid = DreamingRecall.MOD_ID, value = Dist.CLIENT)
public final class DreamingRecallClientEvents {
    private static final long PLAYER_VISUAL_SAMPLE_INTERVAL_NANOS = 50_000_000L;

    private static long lastCameraSampleNanos;
    private static long lastPlayerVisualSampleNanos;
    private static boolean startLocalRecordingWhenReady;

    static {
        CaptureBridge.setClientSink(ClientRecordingManager.INSTANCE);
    }

    private DreamingRecallClientEvents() {
    }

    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && (ReplayWorldController.isReplayLevel(minecraft.level)
                || PacketReplayViewController.isReplayLevel(minecraft.level))) {
            startLocalRecordingWhenReady = false;
        }
        if (startLocalRecordingWhenReady && minecraft.level != null && minecraft.player != null) {
            ClientRecordingManager.INSTANCE.start(minecraft);
            startLocalRecordingWhenReady = !ClientRecordingManager.INSTANCE.isRecording();
        }
        ClientRecordingManager.INSTANCE.tick(minecraft);
        while (DreamingRecallClientModEvents.OPEN_ARCHIVES.consumeClick()) {
            if (!(minecraft.screen instanceof ReplayLibraryScreen)) {
                minecraft.setScreen(new ReplayLibraryScreen(minecraft.screen));
            }
        }
        while (DreamingRecallClientModEvents.TOGGLE_CAMERA_CAPTURE.consumeClick()) {
            boolean enabled = !DreamingRecallClientConfig.CAPTURE_CAMERA_TRACK.get();
            DreamingRecallClientConfig.CAPTURE_CAMERA_TRACK.set(enabled);
            DreamingRecallClientConfig.SPEC.save();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(
                        Component.translatable("message.dreamingrecall.camera_capture", enabled),
                        false
                );
            }
        }
    }

    @SubscribeEvent
    public static void prepareReplayAnimations(RenderFrameEvent.Pre event) {
        ReplayWorldController.prepareActiveAnimations(
                event.getPartialTick().getGameTimeDeltaPartialTick(false)
        );
    }

    @SubscribeEvent
    public static void suppressReplayCameraEntities(RenderLivingEvent.Pre<?, ?> event) {
        if (ReplayWorldController.shouldSuppressReplayEntityRender(event.getEntity())
                || PacketReplayViewController.shouldSuppressReplayEntityRender(event.getEntity())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void renderFrame(RenderFrameEvent.Post event) {
        sendClientSamples(
                Minecraft.getInstance(),
                event.getPartialTick().getGameTimeDeltaPartialTick(false)
        );
    }

    @SubscribeEvent
    public static void suppressVanillaReplayHud(RenderGuiEvent.Pre event) {
        if (Minecraft.getInstance().screen instanceof ReplayTimelineScreen replay
                && !replay.shouldRenderVanillaHud()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void playerLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
        if (ReplayPacketDispatchContext.isActive() || !DreamingRecallClientConfig.RECORD_ON_JOIN.get()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        lastCameraSampleNanos = 0;
        lastPlayerVisualSampleNanos = 0;
        if (minecraft.getSingleplayerServer() != null) {
            ClientRecordingManager.INSTANCE.start(minecraft);
        } else if (serverSupports(minecraft, StartRecordingRequestPayload.TYPE.id())) {
            PacketDistributor.sendToServer(StartRecordingRequestPayload.INSTANCE);
        } else {
            startLocalRecordingWhenReady = true;
            ClientRecordingManager.INSTANCE.start(minecraft);
            startLocalRecordingWhenReady = !ClientRecordingManager.INSTANCE.isRecording();
        }
    }

    @SubscribeEvent
    public static void playerLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (ReplayPacketDispatchContext.isActive()) {
            return;
        }
        startLocalRecordingWhenReady = false;
        ClientRecordingManager.INSTANCE.stop(Minecraft.getInstance());
    }

    @SubscribeEvent
    public static void chunkLoaded(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ClientLevel level && event.getChunk() instanceof LevelChunk chunk) {
            ClientRecordingManager.INSTANCE.chunkLoaded(level, chunk);
        }
    }

    @SubscribeEvent
    public static void chunkUnloaded(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel level && event.getChunk() instanceof LevelChunk chunk) {
            ClientRecordingManager.INSTANCE.chunkUnloaded(level, chunk);
        }
    }

    @SubscribeEvent
    public static void entityJoined(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ClientLevel level) {
            ClientRecordingManager.INSTANCE.entityJoined(level, event.getEntity());
        }
    }

    @SubscribeEvent
    public static void entityLeft(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ClientLevel level) {
            ClientRecordingManager.INSTANCE.entityLeft(level, event.getEntity());
        }
    }

    @SubscribeEvent
    public static void chatReceived(ClientChatReceivedEvent event) {
        if (event.isCanceled()
                || event instanceof ClientChatReceivedEvent.System system && system.isOverlay()) {
            return;
        }
        ClientRecordingManager.INSTANCE.chatReceived(
                event.getMessage(),
                event.isSystem() ? "system" : "player"
        );
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void soundAtPosition(PlayLevelSoundEvent.AtPosition event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ClientLevel level)) {
            return;
        }
        Holder<SoundEvent> sound = event.getSound();
        if (sound != null) {
            ClientRecordingManager.INSTANCE.soundPlayed(
                    level,
                    sound,
                    event.getSource(),
                    event.getPosition(),
                    event.getNewVolume(),
                    event.getNewPitch()
            );
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void soundAtEntity(PlayLevelSoundEvent.AtEntity event) {
        if (event.isCanceled() || !(event.getLevel() instanceof ClientLevel level)) {
            return;
        }
        Holder<SoundEvent> sound = event.getSound();
        Entity entity = event.getEntity();
        if (sound != null) {
            ClientRecordingManager.INSTANCE.soundPlayed(
                    level,
                    sound,
                    event.getSource(),
                    entity.position(),
                    event.getNewVolume(),
                    event.getNewPitch()
            );
        }
    }

    @SubscribeEvent
    public static void screenInitialized(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen titleScreen) {
            event.addListener(Button.builder(
                            Component.translatable("screen.dreamingrecall.archives"),
                            button -> Minecraft.getInstance().setScreen(new ReplayLibraryScreen(titleScreen))
                    )
                    .bounds(8, 8, 104, 20)
                    .tooltip(Tooltip.create(Component.translatable("tooltip.dreamingrecall.archives")))
                    .build());
            event.addListener(recordOnJoinButton(8, 32));
        } else if (event.getScreen() instanceof SelectWorldScreen
                || event.getScreen() instanceof JoinMultiplayerScreen
                || event.getScreen() instanceof DirectJoinServerScreen) {
            event.addListener(recordOnJoinButton(8, 8));
        }
    }

    private static CycleButton<Boolean> recordOnJoinButton(int x, int y) {
        CycleButton<Boolean> button = CycleButton.onOffBuilder(DreamingRecallClientConfig.RECORD_ON_JOIN.get())
                .create(
                        x,
                        y,
                        150,
                        20,
                        Component.translatable("screen.dreamingrecall.record_on_join"),
                        (cycleButton, enabled) -> {
                            DreamingRecallClientConfig.RECORD_ON_JOIN.set(enabled);
                            DreamingRecallClientConfig.SPEC.save();
                        }
                );
        button.setTooltip(Tooltip.create(Component.translatable("tooltip.dreamingrecall.record_on_join")));
        return button;
    }

    private static void sendClientSamples(Minecraft minecraft, float partialTick) {
        if (minecraft.player == null
                || minecraft.level == null
                || minecraft.getConnection() == null
                || ReplayWorldController.isReplayLevel(minecraft.level)
                || PacketReplayViewController.isReplayLevel(minecraft.level)) {
            return;
        }
        long now = System.nanoTime();
        boolean localRecording = ClientRecordingManager.INSTANCE.isRecording();
        boolean uploadPlayerVisual = serverSupports(minecraft, PlayerVisualSamplePayload.TYPE.id());
        boolean uploadCamera = serverSupports(minecraft, CameraSamplePayload.TYPE.id());
        boolean sendPlayerVisual = (localRecording || uploadPlayerVisual)
                && now - lastPlayerVisualSampleNanos >= PLAYER_VISUAL_SAMPLE_INTERVAL_NANOS;
        long cameraInterval = 1_000_000_000L / DreamingRecallClientConfig.CAMERA_SAMPLE_HZ.get();
        boolean sendCamera = DreamingRecallClientConfig.CAPTURE_CAMERA_TRACK.get()
                && (localRecording || uploadCamera)
                && now - lastCameraSampleNanos >= cameraInterval;
        if (!sendPlayerVisual && !sendCamera) {
            return;
        }
        LocalPlayer player = minecraft.player;
        Vec3 playerPosition = player.getPosition(partialTick);
        Vec3 velocity = player.getDeltaMovement();
        CameraSamplePayload.PlayerVisual visual = new CameraSamplePayload.PlayerVisual(
                playerPosition.x(),
                playerPosition.y(),
                playerPosition.z(),
                Mth.rotLerp(partialTick, player.yRotO, player.getYRot()),
                Mth.lerp(partialTick, player.xRotO, player.getXRot()),
                Mth.rotLerp(partialTick, player.yHeadRotO, player.yHeadRot),
                Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot),
                velocity.x(),
                velocity.y(),
                velocity.z(),
                player.getPose().name(),
                player.onGround(),
                player.walkAnimation.position(partialTick),
                player.walkAnimation.speed(partialTick),
                player.getAttackAnim(partialTick),
                player.swinging,
                player.swingTime,
                (player.swingingArm == null ? InteractionHand.MAIN_HAND : player.swingingArm).name(),
                player.isUsingItem(),
                player.getUsedItemHand().name(),
                player.getUseItemRemainingTicks(),
                player.getSwimAmount(partialTick),
                player.getFallFlyingTicks()
        );
        if (sendPlayerVisual) {
            lastPlayerVisualSampleNanos = now;
            if (localRecording) {
                ClientRecordingManager.INSTANCE.playerVisualSample(now, visual);
            }
            if (uploadPlayerVisual) {
                PacketDistributor.sendToServer(new PlayerVisualSamplePayload(now, visual));
            }
        }
        if (sendCamera) {
            lastCameraSampleNanos = now;
            net.minecraft.client.Camera camera = minecraft.gameRenderer.getMainCamera();
            Vec3 position = camera.getPosition();
            CameraSamplePayload sample = new CameraSamplePayload(
                    now,
                    position.x(),
                    position.y(),
                    position.z(),
                    camera.getYRot(),
                    camera.getXRot(),
                    0.0F,
                    minecraft.options.fov().get(),
                    visual
            );
            if (localRecording) {
                ClientRecordingManager.INSTANCE.cameraSample(sample);
            }
            if (uploadCamera) {
                PacketDistributor.sendToServer(sample);
            }
        }
    }

    private static boolean serverSupports(Minecraft minecraft, net.minecraft.resources.ResourceLocation payloadId) {
        return minecraft.getConnection() != null
                && NetworkRegistry.hasChannel(minecraft.getConnection(), payloadId);
    }
}
