package com.hhy.dreamingrecall.client.screen;

import com.hhy.dreamingrecall.DreamingRecall;
import com.hhy.dreamingrecall.client.library.ClientArchiveEntry;
import com.hhy.dreamingrecall.client.playback.ReplayClock;
import com.hhy.dreamingrecall.client.playback.ReplayViewController;
import com.hhy.dreamingrecall.client.playback.ReplayWorldController;
import com.hhy.dreamingrecall.client.playback.packet.PacketReplayController;
import com.hhy.dreamingrecall.client.playback.packet.PacketReplayIndex;
import com.hhy.dreamingrecall.director.CameraInterpolation;
import com.hhy.dreamingrecall.director.CameraKeyframe;
import com.hhy.dreamingrecall.director.CameraPose;
import com.hhy.dreamingrecall.director.CameraTrack;
import com.hhy.dreamingrecall.director.DirectorProject;
import com.hhy.dreamingrecall.director.DirectorProjectStore;
import com.hhy.dreamingrecall.playback.source.LocalArchiveDataSource;
import com.hhy.dreamingrecall.playback.state.ReplayForwardCursor;
import com.hhy.dreamingrecall.playback.state.ReplayPlaybackFrame;
import com.hhy.dreamingrecall.playback.state.ReplayStateIndex;
import com.hhy.dreamingrecall.playback.state.ReplayStateAccumulator;
import com.hhy.dreamingrecall.playback.state.ReplayStateCheckpoint;
import com.hhy.dreamingrecall.playback.state.ReplayStateMaterializer;
import com.hhy.dreamingrecall.playback.state.ReplayWorldSnapshot;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.SharedConstants;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;

public final class ReplayTimelineScreen extends Screen {
    private static final double[] SPEEDS = {0.25, 0.5, 1.0, 2.0, 4.0};
    private static final long MOTION_LOOKAHEAD_NANOS = 250_000_000L;

    private final Screen parent;
    private final ClientArchiveEntry archive;

    private LocalArchiveDataSource source;
    private ReplayStateMaterializer materializer;
    private PacketReplayController packetController;
    private PacketReplayIndex packetIndex;
    private ReplayForwardCursor forwardCursor;
    private ReplayStateIndex index;
    private ReplayWorldSnapshot snapshot;
    private ReplayViewController worldController;
    private ReplayWorldController.ApplyResult worldResult;
    private TimelineSlider timeline;
    private Button playButton;
    private Button speedButton;
    private Button cameraButton;
    private Button dimensionButton;
    private Button playerButton;
    private Button directorButton;
    private Button keyframeButton;
    private Button deleteKeyframeButton;
    private Button hudButton;
    private final List<AbstractWidget> hudWidgets = new ArrayList<>();
    private Component stateMessage = Component.translatable("screen.dreamingrecall.timeline.loading");
    private Throwable failure;
    private boolean closed;
    private boolean playing;
    private boolean scrubbing;
    private boolean resumeAfterScrub;
    private long positionNanos;
    private long lastTickNanos;
    private long seekGeneration;
    private long cursorGeneration;
    private boolean advanceInFlight;
    private int speedIndex = 2;
    private DirectorProject directorProject;
    private Path directorProjectPath;
    private UUID selectedKeyframe;
    private boolean directorMode;
    private boolean draggingKeyframe;
    private boolean directorLoadInFlight;
    private CompletableFuture<Void> directorSaveTail = CompletableFuture.completedFuture(null);
    private Component directorMessage = Component.empty();
    private long transientCursorNanos = -1;
    private boolean hudVisible = true;
    private boolean semanticFallbackStarting;
    private long semanticFallbackTarget = -1;

    public ReplayTimelineScreen(Screen parent, ClientArchiveEntry archive) {
        super(Component.translatable("screen.dreamingrecall.timeline.title"));
        this.parent = parent;
        this.archive = archive;
    }

    @Override
    protected void init() {
        hudWidgets.clear();
        int center = width / 2;
        boolean compact = width < 900;
        int toolbarY = compact ? 28 : 4;
        int toolbarLeft = compact ? 4 : 60;
        int toolbarRight = compact ? width - 4 : width - 60;
        int toolbarGap = 4;
        int toolbarWidth = compact
                ? Math.max(44, (toolbarRight - toolbarLeft - toolbarGap * 5) / 6)
                : 110;
        int rightGroupX = toolbarRight - toolbarWidth * 3 - toolbarGap * 2;
        int cameraX = toolbarLeft;
        int dimensionX = toolbarLeft + toolbarWidth + toolbarGap;
        int playerX = toolbarLeft + (toolbarWidth + toolbarGap) * 2;
        int directorX = compact ? toolbarLeft + (toolbarWidth + toolbarGap) * 3 : rightGroupX;
        int keyframeX = directorX + toolbarWidth + toolbarGap;
        int deleteX = keyframeX + toolbarWidth + toolbarGap;

        timeline = addHudWidget(new TimelineSlider(8, height - 46, Math.max(40, width - 16), 20));
        playButton = addHudWidget(Button.builder(Component.empty(), button -> togglePlaying())
                .bounds(center - 112, height - 22, 72, 20)
                .build());
        speedButton = addHudWidget(Button.builder(Component.empty(), button -> cycleSpeed())
                .bounds(center - 36, height - 22, 72, 20)
                .tooltip(Tooltip.create(Component.translatable("tooltip.dreamingrecall.timeline.speed")))
                .build());
        cameraButton = addHudWidget(Button.builder(Component.empty(), button -> cycleCameraMode())
                .bounds(cameraX, toolbarY, toolbarWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("tooltip.dreamingrecall.timeline.camera")))
                .build());
        dimensionButton = addHudWidget(Button.builder(Component.empty(), button -> cycleDimension())
                .bounds(dimensionX, toolbarY, toolbarWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("tooltip.dreamingrecall.timeline.dimension")))
                .build());
        playerButton = addHudWidget(Button.builder(Component.empty(), button -> cyclePlayer())
                .bounds(playerX, toolbarY, toolbarWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("tooltip.dreamingrecall.timeline.player")))
                .build());
        directorButton = addHudWidget(Button.builder(Component.empty(), button -> toggleDirectorMode())
                .bounds(directorX, toolbarY, toolbarWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("tooltip.dreamingrecall.timeline.director")))
                .build());
        keyframeButton = addHudWidget(Button.builder(Component.empty(), button -> addOrUpdateKeyframe())
                .bounds(keyframeX, toolbarY, toolbarWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("tooltip.dreamingrecall.timeline.keyframe")))
                .build());
        deleteKeyframeButton = addHudWidget(Button.builder(Component.empty(), button -> deleteSelectedKeyframe())
                .bounds(deleteX, toolbarY, toolbarWidth, 20)
                .tooltip(Tooltip.create(Component.translatable("tooltip.dreamingrecall.timeline.delete_keyframe")))
                .build());
        addHudWidget(Button.builder(
                        Component.translatable("screen.dreamingrecall.archives.folder"),
                        button -> Util.getPlatform().openPath(archive.directory())
                )
                .bounds(center + 40, height - 22, 72, 20)
                .build());
        addHudWidget(Button.builder(Component.translatable("gui.back"), button -> onClose())
                .bounds(4, 4, 52, 20)
                .build());
        hudButton = addRenderableWidget(Button.builder(
                        Component.literal("HUD"),
                        button -> toggleHud()
                )
                .bounds(Math.max(4, width - 52), 4, 48, 20)
                .tooltip(Tooltip.create(Component.translatable("tooltip.dreamingrecall.timeline.hud")))
                .build());
        applyHudVisibility();
        updateButtons();
        if (materializer == null && failure == null) {
            openArchive();
        }
    }

    @Override
    public void tick() {
        updatePlaybackFrameClock();
    }

    private void updatePlaybackFrameClock() {
        long now = System.nanoTime();
        if (lastTickNanos == 0) {
            lastTickNanos = now;
        }
        long elapsed = Math.max(0, now - lastTickNanos);
        lastTickNanos = now;
        if (!playing || scrubbing || index == null) {
            if (packetController != null && minecraft.level != null) {
                ReplayClock.prepare(minecraft.level, elapsed, 0.0, false);
            }
            updateFreeCameraFromKeys(elapsed / 1_000_000_000.0);
            applyDirectorPoseAt(positionNanos);
            return;
        }
        long advance = (long) (elapsed * SPEEDS[speedIndex]);
        positionNanos = Math.min(index.durationNanos(), positionNanos + advance);
        if (positionNanos >= index.durationNanos()) {
            playing = false;
            updateButtons();
        }
        timeline.setArchiveNanos(positionNanos);
        requestAdvance(positionNanos);
        applyDirectorPoseAt(positionNanos);
        if (packetController != null && minecraft.level != null) {
            int extraTicks = ReplayClock.prepare(
                    minecraft.level,
                    elapsed,
                    SPEEDS[speedIndex],
                    true
            );
            ReplayClock.runExtraTicks(minecraft.level, extraTicks);
        }
        updateFreeCameraFromKeys(elapsed / 1_000_000_000.0);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (minecraft.level == null) {
            graphics.fill(0, 0, width, height, 0xFF101318);
        }
        if (hudVisible) {
            int topHeight = width < 900 ? 52 : 28;
            graphics.fill(0, 0, width, topHeight, 0x90000000);
            graphics.fill(0, height - 48, width, height, 0x90000000);
        }
        for (var renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
        if (hudVisible) {
            graphics.drawCenteredString(
                    font,
                    fitText(Component.literal(archive.displayName()), Math.max(40, width - 180)),
                    width / 2,
                    10,
                    0xFFFFFFFF
            );
            renderSummary(graphics);
            renderDirectorOverlay(graphics);
        }
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseReleased(mouseX, mouseY, button);
        if (button == 0 && draggingKeyframe) {
            endKeyframeDrag();
            return true;
        }
        if (button == 0 && scrubbing) {
            endScrub();
            return true;
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && draggingKeyframe) {
            dragSelectedKeyframe(mouseX);
            return true;
        }
        if (button == 0 && worldController != null
                && worldController.cameraMode() == ReplayWorldController.CameraMode.FREE
                && !scrubbing) {
            worldController.turnFreeCamera((float) (dragX * 0.15), (float) (dragY * 0.15));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (hudVisible && button == 0 && beginKeyframeDrag(mouseX, mouseY)) {
            clearFocus();
            return true;
        }
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (!handled) {
            clearFocus();
        }
        return handled;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if ((modifiers & GLFW.GLFW_MOD_CONTROL) != 0 && keyCode == GLFW.GLFW_KEY_S) {
            saveDirectorProject();
            return true;
        }
        if (worldController != null) {
            if (worldController.cameraMode() != ReplayWorldController.CameraMode.FREE
                    && minecraft.options.keyShift.matches(keyCode, scanCode)) {
                worldController.setCameraMode(ReplayWorldController.CameraMode.FREE);
                updateButtons();
                return true;
            }
            if (worldController.cameraMode() == ReplayWorldController.CameraMode.FREE
                    && isFreeCameraControlKey(keyCode, scanCode)) {
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_H) {
            toggleHud();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_F) {
            cycleCameraMode();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_P) {
            cyclePlayer();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_K) {
            addOrUpdateKeyframe();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DELETE || keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            deleteSelectedKeyframe();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_I) {
            toggleSelectedInterpolation();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_L) {
            toggleDirectorMode();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private boolean isFreeCameraControlKey(int keyCode, int scanCode) {
        return minecraft.options.keyUp.matches(keyCode, scanCode)
                || minecraft.options.keyDown.matches(keyCode, scanCode)
                || minecraft.options.keyLeft.matches(keyCode, scanCode)
                || minecraft.options.keyRight.matches(keyCode, scanCode)
                || minecraft.options.keyJump.matches(keyCode, scanCode)
                || minecraft.options.keyShift.matches(keyCode, scanCode)
                || minecraft.options.keySprint.matches(keyCode, scanCode);
    }

    @Override
    public void removed() {
        closed = true;
        if (packetController != null) {
            packetController.close();
            packetController = null;
            worldController = null;
        } else if (worldController != null) {
            worldController.close();
            worldController = null;
        }
        closeForwardCursor();
        if (materializer != null) {
            materializer.close();
            materializer = null;
        }
        if (source != null) {
            source.close();
            source = null;
        }
        saveDirectorProject();
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    private void openArchive() {
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return LocalArchiveDataSource.open(
                                archive.directory(),
                                SharedConstants.getCurrentVersion().getName()
                        );
                    } catch (Exception openFailure) {
                        throw new java.util.concurrent.CompletionException(openFailure);
                    }
                }, Util.ioPool())
                .whenComplete((opened, openFailure) -> minecraft.execute(() -> {
                    if (closed || minecraft.screen != this) {
                        if (opened != null) {
                            opened.close();
                        }
                        return;
                    }
                    if (openFailure != null) {
                        fail(openFailure);
                        return;
                    }
                    source = opened;
                    initializePacketBackend(opened);
                }));
    }

    private void initializePacketBackend(LocalArchiveDataSource opened) {
        PacketReplayController candidate = new PacketReplayController(minecraft, opened);
        packetController = candidate;
        candidate.buildIndex().whenComplete((built, packetFailure) -> minecraft.execute(() -> {
            if (closed || minecraft.screen != this || packetController != candidate) {
                candidate.close();
                return;
            }
            if (packetFailure != null || built == null || !built.playable()) {
                if (packetFailure != null) {
                    DreamingRecall.LOGGER.warn(
                            "Packet replay indexing failed; using portable fallback",
                            rootCause(packetFailure)
                    );
                } else {
                    DreamingRecall.LOGGER.info("Archive has no playable packet track; using portable fallback");
                }
                candidate.close();
                packetController = null;
                initializeSemanticBackend(packetFailure);
                return;
            }
            packetIndex = built;
            DreamingRecall.LOGGER.info(
                    "Packet replay index ready: {} packets, {} bootstrap frames, {} tracks, world starts at {} ns",
                    built.packetCount(),
                    built.bootstrapFrames().size(),
                    built.tracks().size(),
                    built.worldStartNanos()
            );
            ReplayWorldSnapshot empty = new ReplayStateAccumulator().snapshotAt(0);
            index = new ReplayStateIndex(
                    List.of(new ReplayStateCheckpoint(0, -1, empty)),
                    built.durationNanos(),
                    built.worldStartNanos()
            );
            backendReady(built.worldStartNanos());
        }));
    }

    private void initializeSemanticBackend(Throwable packetFailure) {
        if (materializer != null || semanticFallbackStarting || source == null) {
            return;
        }
        semanticFallbackStarting = true;
        materializer = new ReplayStateMaterializer(source);
        materializer.buildIndex().whenComplete((built, indexFailure) -> minecraft.execute(() -> {
            semanticFallbackStarting = false;
            if (closed || minecraft.screen != this) {
                return;
            }
            if (indexFailure != null) {
                if (packetFailure != null) {
                    indexFailure.addSuppressed(rootCause(packetFailure));
                }
                fail(indexFailure);
                return;
            }
            index = built;
            DreamingRecall.LOGGER.info("Portable replay fallback index ready");
            long requested = semanticFallbackTarget;
            semanticFallbackTarget = -1;
            backendReady(requested >= 0
                    ? Math.min(requested, built.durationNanos())
                    : built.firstPopulatedNanos());
        }));
    }

    private void backendReady(long firstVisible) {
        stateMessage = Component.translatable("screen.dreamingrecall.timeline.ready");
        timeline.active = true;
        playButton.active = index.durationNanos() > 0;
        speedButton.active = true;
        loadDirectorProject();
        positionNanos = firstVisible;
        timeline.setArchiveNanos(firstVisible);
        requestSeek(firstVisible);
    }

    private void fail(Throwable failed) {
        failure = rootCause(failed);
        DreamingRecall.LOGGER.error("Replay playback failed", failure);
        playing = false;
        stateMessage = Component.translatable(
                "screen.dreamingrecall.timeline.failed",
                failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()
        );
        updateButtons();
    }

    private void togglePlaying() {
        if (index == null || index.durationNanos() == 0) {
            return;
        }
        if (positionNanos >= index.durationNanos()) {
            positionNanos = 0;
            timeline.setArchiveNanos(0);
            requestSeek(0);
        }
        playing = !playing;
        lastTickNanos = System.nanoTime();
        updateButtons();
    }

    private void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
        updateButtons();
    }

    private void cycleCameraMode() {
        if (worldController == null) {
            return;
        }
        ReplayWorldController.CameraMode[] modes = ReplayWorldController.CameraMode.values();
        int next = (worldController.cameraMode().ordinal() + 1) % modes.length;
        worldController.setCameraMode(modes[next]);
        updateButtons();
    }

    private void cycleDimension() {
        if (worldController == null) {
            return;
        }
        java.util.List<String> dimensions = worldController.dimensions();
        if (dimensions.isEmpty()) {
            return;
        }
        int current = dimensions.indexOf(worldController.activeDimension());
        worldController.selectDimension(dimensions.get((current + 1 + dimensions.size()) % dimensions.size()));
        updateButtons();
    }

    private void cyclePlayer() {
        if (worldController == null) {
            return;
        }
        java.util.List<ReplayWorldController.PlayerTarget> players = worldController.playerTargets();
        if (players.isEmpty()) {
            return;
        }
        UUID current = attachedPlayer();
        int index = -1;
        if (current != null) {
            index = java.util.stream.IntStream.range(0, players.size())
                    .filter(value -> players.get(value).uuid().equals(current))
                    .findFirst()
                    .orElse(-1);
        }
        ReplayWorldController.PlayerTarget target = players.get((index + 1 + players.size()) % players.size());
        worldController.attachPlayer(target.uuid(), false);
        updateButtons();
    }

    private UUID attachedPlayer() {
        return worldController == null
                ? null
                : worldController.attachedPlayerTarget().map(ReplayWorldController.PlayerTarget::uuid).orElse(null);
    }

    private void loadDirectorProject() {
        if (directorLoadInFlight || directorProject != null || index == null) {
            return;
        }
        directorLoadInFlight = true;
        Path gameDirectory = minecraft.gameDirectory.toPath();
        directorProjectPath = DirectorProjectStore.path(gameDirectory, archive.manifest().archiveId());
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return DirectorProjectStore.loadOrCreate(
                                gameDirectory,
                                archive.manifest().archiveId(),
                                archive.displayName()
                        );
                    } catch (java.io.IOException failure) {
                        throw new java.util.concurrent.CompletionException(failure);
                    }
                }, Util.ioPool())
                .whenComplete((project, loadFailure) -> minecraft.execute(() -> {
                    directorLoadInFlight = false;
                    if (closed || minecraft.screen != this) {
                        return;
                    }
                    if (loadFailure != null) {
                        directorMessage = Component.translatable(
                                "screen.dreamingrecall.timeline.director_failed",
                                rootCause(loadFailure).getMessage()
                        );
                    } else {
                        directorProject = project;
                        directorMessage = Component.translatable(
                                "screen.dreamingrecall.timeline.director_ready",
                                project.cameraTrack().keyframes().size()
                        );
                    }
                    updateButtons();
                }));
    }

    private void saveDirectorProject() {
        DirectorProject project = directorProject;
        if (project == null) {
            return;
        }
        Path gameDirectory = minecraft.gameDirectory.toPath();
        directorSaveTail = directorSaveTail
                .handle((ignored, failure) -> null)
                .thenRunAsync(() -> {
                    try {
                        DirectorProjectStore.save(gameDirectory, project);
                    } catch (java.io.IOException failure) {
                        throw new java.util.concurrent.CompletionException(failure);
                    }
                }, Util.ioPool())
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        minecraft.execute(() -> directorMessage = Component.translatable(
                                "screen.dreamingrecall.timeline.director_failed",
                                rootCause(failure).getMessage()
                        ));
                    }
                });
    }

    private void toggleDirectorMode() {
        if (directorProject == null || worldController == null) {
            return;
        }
        directorMode = !directorMode;
        if (directorMode && directorProject.cameraTrack().keyframes().isEmpty()) {
            directorMessage = Component.translatable("screen.dreamingrecall.timeline.director_empty");
        } else if (directorMode) {
            applyDirectorPoseAt(positionNanos);
        }
        updateButtons();
    }

    private void addOrUpdateKeyframe() {
        if (directorProject == null || worldController == null || index == null) {
            return;
        }
        long time = Math.max(0, Math.min(index.durationNanos(), positionNanos));
        CameraPose pose = worldController.cameraPose();
        CameraTrack track = directorProject.cameraTrack();
        CameraKeyframe existing = track.keyframes().stream()
                .filter(keyframe -> keyframe.archiveNanos() == time)
                .findFirst()
                .orElse(null);
        CameraKeyframe replacement = existing == null
                ? CameraKeyframe.create(time, pose, CameraInterpolation.SMOOTH)
                : new CameraKeyframe(existing.id(), time, pose, existing.interpolationToNext());
        directorProject = directorProject.withCameraTrack(
                existing == null ? track.add(replacement) : track.update(replacement)
        );
        selectedKeyframe = replacement.id();
        directorMode = true;
        directorMessage = Component.translatable(
                "screen.dreamingrecall.timeline.keyframe_saved",
                directorProject.cameraTrack().keyframes().size()
        );
        saveDirectorProject();
        updateButtons();
    }

    private void deleteSelectedKeyframe() {
        if (directorProject == null || directorProject.cameraTrack().keyframes().isEmpty()) {
            return;
        }
        CameraKeyframe target = selectedKeyframe == null
                ? nearestKeyframe(positionNanos)
                : directorProject.cameraTrack().keyframes().stream()
                .filter(keyframe -> keyframe.id().equals(selectedKeyframe))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return;
        }
        directorProject = directorProject.withCameraTrack(directorProject.cameraTrack().remove(target.id()));
        selectedKeyframe = null;
        directorMessage = Component.translatable(
                "screen.dreamingrecall.timeline.keyframe_deleted",
                directorProject.cameraTrack().keyframes().size()
        );
        saveDirectorProject();
        updateButtons();
    }

    private void toggleSelectedInterpolation() {
        if (directorProject == null) {
            return;
        }
        CameraKeyframe target = selectedKeyframe == null ? nearestKeyframe(positionNanos) : directorProject.cameraTrack()
                .keyframes().stream()
                .filter(keyframe -> keyframe.id().equals(selectedKeyframe))
                .findFirst()
                .orElse(null);
        if (target == null) {
            return;
        }
        CameraInterpolation next = target.interpolationToNext() == CameraInterpolation.LINEAR
                ? CameraInterpolation.SMOOTH
                : CameraInterpolation.LINEAR;
        directorProject = directorProject.withCameraTrack(directorProject.cameraTrack().update(
                new CameraKeyframe(target.id(), target.archiveNanos(), target.pose(), next)
        ));
        directorMessage = Component.translatable("screen.dreamingrecall.timeline.interpolation", next.name());
        saveDirectorProject();
        updateButtons();
    }

    private boolean beginKeyframeDrag(double mouseX, double mouseY) {
        if (directorProject == null || timeline == null || index == null || index.durationNanos() <= 0) {
            return false;
        }
        if (mouseY < timeline.getY() - 8 || mouseY > timeline.getY() + timeline.getHeight() + 8) {
            return false;
        }
        CameraKeyframe target = nearestKeyframeForPixel(mouseX);
        if (target == null) {
            return false;
        }
        selectedKeyframe = target.id();
        draggingKeyframe = true;
        playing = false;
        closeForwardCursor();
        updateButtons();
        return true;
    }

    private void dragSelectedKeyframe(double mouseX) {
        if (!draggingKeyframe || directorProject == null || index == null || selectedKeyframe == null) {
            return;
        }
        long targetTime = timeAtTimelinePixel(mouseX);
        CameraKeyframe selected = directorProject.cameraTrack().keyframes().stream()
                .filter(keyframe -> keyframe.id().equals(selectedKeyframe))
                .findFirst()
                .orElse(null);
        if (selected == null || targetTime == selected.archiveNanos()) {
            return;
        }
        boolean collision = directorProject.cameraTrack().keyframes().stream()
                .anyMatch(keyframe -> !keyframe.id().equals(selectedKeyframe)
                        && keyframe.archiveNanos() == targetTime);
        if (collision) {
            return;
        }
        directorProject = directorProject.withCameraTrack(directorProject.cameraTrack().update(
                new CameraKeyframe(selected.id(), targetTime, selected.pose(), selected.interpolationToNext())
        ));
        positionNanos = targetTime;
        timeline.setArchiveNanos(targetTime);
        applyDirectorPoseAt(targetTime);
    }

    private void endKeyframeDrag() {
        draggingKeyframe = false;
        saveDirectorProject();
        requestSeek(positionNanos);
        updateButtons();
    }

    private void applyDirectorPoseAt(long time) {
        if (!directorMode || directorProject == null || worldController == null) {
            return;
        }
        directorProject.cameraTrack().evaluate(Math.max(0, time)).ifPresent(pose -> {
            try {
                worldController.applyDirectorPose(pose);
            } catch (RuntimeException failure) {
                directorMode = false;
                directorMessage = Component.translatable(
                        "screen.dreamingrecall.timeline.director_failed",
                        failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage()
                );
                updateButtons();
            }
        });
    }

    private CameraKeyframe nearestKeyframe(long time) {
        if (directorProject == null || directorProject.cameraTrack().keyframes().isEmpty()) {
            return null;
        }
        return directorProject.cameraTrack().keyframes().stream()
                .min(java.util.Comparator.comparingLong(keyframe -> Math.abs(keyframe.archiveNanos() - time)))
                .orElse(null);
    }

    private CameraKeyframe nearestKeyframeForPixel(double mouseX) {
        CameraKeyframe nearest = null;
        double distance = Double.MAX_VALUE;
        for (CameraKeyframe keyframe : directorProject.cameraTrack().keyframes()) {
            double keyX = timelinePixel(keyframe.archiveNanos());
            double candidate = Math.abs(keyX - mouseX);
            if (candidate < distance) {
                distance = candidate;
                nearest = keyframe;
            }
        }
        return distance <= 8.0 ? nearest : null;
    }

    private long timeAtTimelinePixel(double mouseX) {
        double fraction = (mouseX - timeline.getX()) / Math.max(1.0, timeline.getWidth());
        fraction = Math.max(0.0, Math.min(1.0, fraction));
        return Math.max(0, Math.min(index.durationNanos(), Math.round(index.durationNanos() * fraction)));
    }

    private int timelinePixel(long time) {
        long duration = index == null ? 0 : index.durationNanos();
        if (duration <= 0 || timeline == null) {
            return timeline == null ? 0 : timeline.getX();
        }
        return timeline.getX() + (int) Math.round((double) time / duration * timeline.getWidth());
    }

    private void beginScrub() {
        if (index == null || scrubbing) {
            return;
        }
        scrubbing = true;
        resumeAfterScrub = playing;
        playing = false;
        closeForwardCursor();
        updateButtons();
    }

    private void previewSeek(long target) {
        positionNanos = target;
        requestSeek(target);
    }

    private void endScrub() {
        if (!scrubbing) {
            return;
        }
        scrubbing = false;
        requestSeek(positionNanos);
        playing = resumeAfterScrub;
        lastTickNanos = System.nanoTime();
        updateButtons();
    }

    private void requestSeek(long target) {
        if (index == null) {
            return;
        }
        if (worldController != null) {
            worldController.notifySeekStarted();
        }
        // Invalidate any in-flight forward step before starting a seek. Its
        // completion may arrive later on the client thread and must not move
        // the newly selected replay position back or forward.
        closeForwardCursor();
        long generation = ++seekGeneration;
        stateMessage = Component.translatable("screen.dreamingrecall.timeline.seeking");
        if (packetController != null) {
            PacketReplayController activePackets = packetController;
            if (target < activePackets.currentNanos()) {
                worldController = null;
            }
            activePackets.seek(target).whenComplete((result, seekFailure) -> minecraft.execute(() -> {
                if (closed || generation != seekGeneration || minecraft.screen != this
                        || packetController != activePackets) {
                    return;
                }
                if (seekFailure != null) {
                    Throwable root = rootCause(seekFailure);
                    if (!(root instanceof java.util.concurrent.CancellationException)) {
                        startSemanticFallback(root, target);
                    }
                    return;
                }
                worldController = activePackets.view();
                if (!result.worldReady() || worldController == null) {
                    startSemanticFallback(new IllegalStateException("Packet replay did not create a client world"), target);
                    return;
                }
                transientCursorNanos = target;
                stateMessage = Component.translatable("screen.dreamingrecall.timeline.exact");
                applyDirectorPoseAt(target);
                updateButtons();
            }));
            return;
        }
        if (materializer == null) {
            return;
        }
        materializer.seek(target).whenComplete((result, seekFailure) -> minecraft.execute(() -> {
            if (closed || generation != seekGeneration || minecraft.screen != this) {
                return;
            }
            if (seekFailure != null) {
                Throwable root = rootCause(seekFailure);
                if (!(root instanceof java.util.concurrent.CancellationException)) {
                    fail(root);
                }
                return;
            }
            snapshot = result;
            applyWorldSnapshot(result, false);
            replaceForwardCursor(result);
            stateMessage = result.baselineComplete()
                    ? Component.translatable("screen.dreamingrecall.timeline.exact")
                    : Component.translatable("screen.dreamingrecall.timeline.incomplete");
        }));
    }

    private void requestAdvance(long target) {
        if (packetController != null) {
            if (advanceInFlight || scrubbing) {
                return;
            }
            PacketReplayController activePackets = packetController;
            long generation = cursorGeneration;
            advanceInFlight = true;
            activePackets.seek(target).whenComplete((result, advanceFailure) -> minecraft.execute(() -> {
                if (closed || generation != cursorGeneration || minecraft.screen != this
                        || packetController != activePackets) {
                    return;
                }
                advanceInFlight = false;
                if (advanceFailure != null) {
                    Throwable root = rootCause(advanceFailure);
                    if (!(root instanceof java.util.concurrent.CancellationException)) {
                        startSemanticFallback(root, target);
                    }
                    return;
                }
                worldController = activePackets.view();
                stateMessage = Component.translatable("screen.dreamingrecall.timeline.exact");
                applyDirectorPoseAt(result.archiveNanos());
                updateButtons();
                if (!scrubbing && result.archiveNanos() < positionNanos) {
                    requestAdvance(positionNanos);
                }
            }));
            return;
        }
        if (forwardCursor == null || advanceInFlight || scrubbing) {
            return;
        }
        long generation = cursorGeneration;
        advanceInFlight = true;
        forwardCursor.advanceFrameTo(target, MOTION_LOOKAHEAD_NANOS)
                .whenComplete((result, advanceFailure) -> minecraft.execute(() -> {
            if (closed || generation != cursorGeneration || minecraft.screen != this) {
                return;
            }
            advanceInFlight = false;
            if (advanceFailure != null) {
                Throwable root = rootCause(advanceFailure);
                if (!(root instanceof java.util.concurrent.CancellationException)) {
                    fail(root);
                }
                return;
            }
            snapshot = result.snapshot();
            applyPlaybackFrame(result, true);
            stateMessage = result.snapshot().baselineComplete()
                    ? Component.translatable("screen.dreamingrecall.timeline.exact")
                    : Component.translatable("screen.dreamingrecall.timeline.incomplete");
            if (!scrubbing && result.snapshot().archiveNanos() < positionNanos) {
                requestAdvance(positionNanos);
            }
        }));
    }

    private void replaceForwardCursor(ReplayWorldSnapshot initialState) {
        closeForwardCursor();
        forwardCursor = new ReplayForwardCursor(source, initialState);
    }

    private void closeForwardCursor() {
        cursorGeneration++;
        advanceInFlight = false;
        if (packetController != null) {
            packetController.cancelSeek();
        }
        if (forwardCursor != null) {
            forwardCursor.close();
            forwardCursor = null;
        }
    }

    private void startSemanticFallback(Throwable packetFailure, long target) {
        DreamingRecall.LOGGER.error(
                "Exact packet replay failed at {} ns; switching to portable fallback",
                target,
                rootCause(packetFailure)
        );
        PacketReplayController failedPackets = packetController;
        packetController = null;
        packetIndex = null;
        worldController = null;
        closeForwardCursor();
        if (failedPackets != null) {
            failedPackets.close();
        }
        index = null;
        stateMessage = Component.translatable("screen.dreamingrecall.timeline.loading");
        semanticFallbackTarget = Math.max(0, target);
        initializeSemanticBackend(packetFailure);
    }

    private void updateButtons() {
        if (playButton != null) {
            playButton.setMessage(Component.translatable(
                    playing ? "screen.dreamingrecall.timeline.pause" : "screen.dreamingrecall.timeline.play"
            ));
            playButton.active = index != null && index.durationNanos() > 0 && failure == null;
        }
        if (speedButton != null) {
            speedButton.setMessage(Component.literal(formatSpeed(SPEEDS[speedIndex])));
            speedButton.active = index != null && failure == null;
        }
        if (timeline != null) {
            timeline.active = index != null && failure == null;
        }
        if (cameraButton != null) {
            cameraButton.setMessage(Component.translatable(
                    "screen.dreamingrecall.timeline.camera",
                    worldController == null ? "-" : worldController.cameraMode().name()
            ));
            cameraButton.active = worldController != null;
        }
        if (dimensionButton != null) {
            dimensionButton.setMessage(Component.translatable(
                    "screen.dreamingrecall.timeline.dimension",
                    worldController == null ? "-" : shortDimension(worldController.activeDimension())
            ));
            dimensionButton.active = worldController != null && worldController.dimensions().size() > 1;
        }
        if (playerButton != null) {
            playerButton.setMessage(Component.translatable(
                    "screen.dreamingrecall.timeline.player",
                    worldController == null ? "-" : playerLabel()
            ));
            playerButton.active = worldController != null && !worldController.playerTargets().isEmpty();
        }
        if (directorButton != null) {
            directorButton.setMessage(Component.translatable(
                    "screen.dreamingrecall.timeline.director",
                    directorMode ? "ON" : "OFF"
            ));
            directorButton.active = directorProject != null && worldController != null;
        }
        if (keyframeButton != null) {
            keyframeButton.setMessage(Component.translatable("screen.dreamingrecall.timeline.keyframe"));
            keyframeButton.active = directorProject != null && worldController != null && index != null;
        }
        if (deleteKeyframeButton != null) {
            deleteKeyframeButton.setMessage(Component.translatable("screen.dreamingrecall.timeline.delete_keyframe"));
            deleteKeyframeButton.active = directorProject != null
                    && !directorProject.cameraTrack().keyframes().isEmpty();
        }
    }

    private <T extends AbstractWidget> T addHudWidget(T widget) {
        hudWidgets.add(widget);
        return addRenderableWidget(widget);
    }

    private void toggleHud() {
        if (scrubbing) {
            endScrub();
        }
        hudVisible = !hudVisible;
        applyHudVisibility();
    }

    private void applyHudVisibility() {
        hudWidgets.forEach(widget -> widget.visible = hudVisible);
        if (hudButton != null) {
            hudButton.visible = true;
        }
    }

    public boolean shouldRenderVanillaHud() {
        return hudVisible
                && worldController != null
                && worldController.cameraMode() == ReplayWorldController.CameraMode.FIRST_PERSON;
    }

    private void renderDirectorOverlay(GuiGraphics graphics) {
        if (timeline == null || directorProject == null || index == null) {
            return;
        }
        java.util.List<CameraKeyframe> keyframes = directorProject.cameraTrack().keyframes();
        for (int i = 0; i < keyframes.size(); i++) {
            CameraKeyframe keyframe = keyframes.get(i);
            int x = timelinePixel(keyframe.archiveNanos());
            int color = keyframe.id().equals(selectedKeyframe) ? 0xFFFFD34E : 0xFF66D9EF;
            graphics.fill(x - 1, timeline.getY() - 4, x + 2, timeline.getY() + timeline.getHeight() + 4, color);
            if (i + 1 < keyframes.size()) {
                int nextX = timelinePixel(keyframes.get(i + 1).archiveNanos());
                graphics.fill(x + 2, timeline.getY() - 1, Math.max(x + 2, nextX), timeline.getY() + 1, 0xAA66D9EF);
            }
        }
        if (!directorMessage.getString().isEmpty()) {
            int top = width < 900 ? 70 : 46;
            graphics.drawString(
                    font,
                    fitText(directorMessage, Math.max(20, width - 16)),
                    8,
                    top,
                    0xFF9CD7E8,
                    false
            );
        }
    }

    private void applyWorldSnapshot(ReplayWorldSnapshot next, boolean emitTransients) {
        if (next.dimensions().isEmpty()) {
            return;
        }
        try {
            ReplayWorldController semantic;
            if (worldController instanceof ReplayWorldController existing) {
                semantic = existing;
            } else {
                semantic = new ReplayWorldController(minecraft, next, archive.manifest().archiveId());
                worldController = semantic;
            }
            worldResult = semantic.applySnapshot(next);
            applyDirectorPoseAt(next.archiveNanos());
            consumeTransientEntries(semantic, next, emitTransients);
            updateButtons();
        } catch (RuntimeException | LinkageError worldFailure) {
            fail(worldFailure);
        }
    }

    private void applyPlaybackFrame(ReplayPlaybackFrame frame, boolean emitTransients) {
        ReplayWorldSnapshot next = frame.snapshot();
        if (next.dimensions().isEmpty()) {
            return;
        }
        try {
            ReplayWorldController semantic;
            if (worldController instanceof ReplayWorldController existing) {
                semantic = existing;
            } else {
                semantic = new ReplayWorldController(minecraft, next, archive.manifest().archiveId());
                worldController = semantic;
            }
            worldResult = semantic.applyPlaybackFrame(frame);
            applyDirectorPoseAt(next.archiveNanos());
            consumeTransientEntries(semantic, next, emitTransients);
            updateButtons();
        } catch (RuntimeException | LinkageError worldFailure) {
            fail(worldFailure);
        }
    }

    private void consumeTransientEntries(
            ReplayWorldController semantic,
            ReplayWorldSnapshot next,
            boolean emit
    ) {
        if (!emit || transientCursorNanos < 0 || next.archiveNanos() < transientCursorNanos) {
            transientCursorNanos = next.archiveNanos();
            return;
        }
        long previous = transientCursorNanos;
        next.recentChat().stream()
                .filter(entry -> entry.archiveNanos() > previous && entry.archiveNanos() <= next.archiveNanos())
                .forEach(entry -> {
                    try {
                        Component message = Component.Serializer.fromJson(
                                entry.delivery().renderedJson(),
                                minecraft.level.registryAccess()
                        );
                        if (message != null) {
                            minecraft.gui.getChat().addMessage(message);
                        }
                    } catch (RuntimeException ignored) {
                        minecraft.gui.getChat().addMessage(Component.literal(entry.delivery().renderedJson()));
                    }
                });
        next.recentSounds().stream()
                .filter(entry -> entry.archiveNanos() > previous && entry.archiveNanos() <= next.archiveNanos())
                .forEach(semantic::playSound);
        next.recentEntityEffects().stream()
                .filter(entry -> entry.archiveNanos() > previous && entry.archiveNanos() <= next.archiveNanos())
                .forEach(semantic::playEntityEffect);
        transientCursorNanos = next.archiveNanos();
    }

    private void updateFreeCameraFromKeys(double elapsedSeconds) {
        if (worldController == null || worldController.cameraMode() != ReplayWorldController.CameraMode.FREE) {
            return;
        }
        double forward = (isPhysicallyDown(minecraft.options.keyUp) ? 1.0 : 0.0)
                - (isPhysicallyDown(minecraft.options.keyDown) ? 1.0 : 0.0);
        double strafe = (isPhysicallyDown(minecraft.options.keyLeft) ? 1.0 : 0.0)
                - (isPhysicallyDown(minecraft.options.keyRight) ? 1.0 : 0.0);
        double vertical = (isPhysicallyDown(minecraft.options.keyJump) ? 1.0 : 0.0)
                - (isPhysicallyDown(minecraft.options.keyShift) ? 1.0 : 0.0);
        if (forward != 0.0 || strafe != 0.0 || vertical != 0.0) {
            worldController.moveFreeCamera(
                    forward,
                    strafe,
                    vertical,
                    isPhysicallyDown(minecraft.options.keySprint),
                    elapsedSeconds
            );
        }
    }

    private boolean isPhysicallyDown(KeyMapping mapping) {
        InputConstants.Key key = mapping.getKey();
        if (key.getType() == InputConstants.Type.KEYSYM
                && key.getValue() != InputConstants.UNKNOWN.getValue()) {
            return InputConstants.isKeyDown(minecraft.getWindow().getWindow(), key.getValue());
        }
        return mapping.isDown();
    }

    private String playerLabel() {
        if (worldController != null) {
            return worldController.attachedPlayerTarget()
                    .map(ReplayWorldController.PlayerTarget::name)
                    .orElse("Free");
        }
        return "Free";
    }

    private static String shortDimension(String id) {
        int separator = id.indexOf(':');
        return separator >= 0 ? id.substring(separator + 1) : id;
    }

    private void renderSummary(GuiGraphics graphics) {
        int top = width < 900 ? 56 : 32;
        int availableWidth = Math.max(20, width - 16);
        graphics.drawString(
                font,
                fitText(stateMessage, availableWidth),
                8,
                top,
                failure == null ? 0xFFB8C4D0 : 0xFFFF7777,
                false
        );
    }

    private int informationLeft() {
        return Math.max(132, width / 2 - 320);
    }

    private int informationRight() {
        return Math.min(width - 132, width / 2 + 320);
    }

    private Component fitText(Component value, int maximumWidth) {
        if (maximumWidth <= 0 || font.width(value) <= maximumWidth) {
            return value;
        }
        String ellipsis = "...";
        int textWidth = Math.max(0, maximumWidth - font.width(ellipsis));
        return Component.literal(font.plainSubstrByWidth(value.getString(), textWidth) + ellipsis);
    }

    private static String formatSpeed(double speed) {
        return String.format(Locale.ROOT, speed < 1.0 ? "%.2fx" : "%.1fx", speed);
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private final class TimelineSlider extends AbstractSliderButton {
        private TimelineSlider(int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty(), 0.0);
            active = false;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            long duration = index == null ? archive.durationNanos() : index.durationNanos();
            setMessage(Component.literal(
                    ReplayLibraryScreen.formatDuration((long) (duration * value))
                            + " / "
                            + ReplayLibraryScreen.formatDuration(duration)
            ));
        }

        @Override
        protected void applyValue() {
            long duration = index == null ? archive.durationNanos() : index.durationNanos();
            previewSeek((long) (duration * value));
            updateMessage();
        }

        @Override
        public void onClick(double mouseX, double mouseY) {
            beginScrub();
            super.onClick(mouseX, mouseY);
        }

        @Override
        public void onRelease(double mouseX, double mouseY) {
            super.onRelease(mouseX, mouseY);
            endScrub();
        }

        private void setArchiveNanos(long archiveNanos) {
            long duration = index == null ? archive.durationNanos() : index.durationNanos();
            value = duration <= 0 ? 0.0 : Math.max(0.0, Math.min(1.0, (double) archiveNanos / duration));
            updateMessage();
        }
    }
}
