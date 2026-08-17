package com.hhy.dreamingrecall.command;

import com.hhy.dreamingrecall.DreamingRecall;
import com.hhy.dreamingrecall.archive.ArchiveManifest;
import com.hhy.dreamingrecall.archive.ArchiveManifestCodec;
import com.hhy.dreamingrecall.config.DreamingRecallConfig;
import com.hhy.dreamingrecall.server.DreamingRecallServer;
import com.hhy.dreamingrecall.server.ServerRecordingStatus;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

@EventBusSubscriber(modid = DreamingRecall.MOD_ID)
public final class DreamingRecallCommands {
    private DreamingRecallCommands() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("dreamingrecall")
                        .requires(DreamingRecallCommands::canAdminister)
                        .then(Commands.literal("record")
                                .then(Commands.literal("start").executes(context -> start(context.getSource())))
                                .then(Commands.literal("stop").executes(context -> stop(context.getSource())))
                                .then(Commands.literal("status").executes(context -> status(context.getSource()))))
                        .then(Commands.literal("status").executes(context -> status(context.getSource())))
                        .then(Commands.literal("archives").executes(context -> archives(context.getSource())))
                        .then(Commands.literal("config")
                                .then(Commands.literal("announce")
                                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                .executes(context -> setAnnounce(
                                                        context.getSource(),
                                                        BoolArgumentType.getBool(context, "enabled")
                                                ))))
                                .then(Commands.literal("autoRecording")
                                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                .executes(context -> setAutoRecording(
                                                        context.getSource(),
                                                        BoolArgumentType.getBool(context, "enabled")
                                                ))))
                                .then(Commands.literal("captureChat")
                                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                .executes(context -> setCaptureChat(
                                                        context.getSource(),
                                                        BoolArgumentType.getBool(context, "enabled")
                                                ))))
                                .then(Commands.literal("clientCameraTracks")
                                        .then(Commands.argument("enabled", BoolArgumentType.bool())
                                                .executes(context -> setClientCameraTracks(
                                                        context.getSource(),
                                                        BoolArgumentType.getBool(context, "enabled")
                                                ))))
                                .then(Commands.literal("automaticQuotaMiB")
                                        .then(Commands.argument("value", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0))
                                                .executes(context -> setAutomaticQuota(
                                                        context.getSource(),
                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "value")
                                                )))))
        );
    }

    private static boolean canAdminister(CommandSourceStack source) {
        return source.hasPermission(2) || !source.getServer().isDedicatedServer();
    }

    private static int start(CommandSourceStack source) {
        return switch (DreamingRecallServer.INSTANCE.startManual(source.getServer())) {
            case STARTED -> success(source, "DreamingRecall recording started.");
            case ALREADY_RECORDING -> failure(source, "DreamingRecall is already recording or finishing an archive.");
            case FAILED -> failure(source, "DreamingRecall could not start; inspect the server log.");
        };
    }

    private static int stop(CommandSourceStack source) {
        return switch (DreamingRecallServer.INSTANCE.stop(source.getServer())) {
            case STOPPING -> success(source, "DreamingRecall is committing the final archive segment in the background.");
            case NOT_RECORDING -> failure(source, "DreamingRecall is not recording.");
        };
    }

    private static int status(CommandSourceStack source) {
        ServerRecordingStatus status = DreamingRecallServer.INSTANCE.status(source.getServer()).orElse(null);
        if (status == null) {
            source.sendSuccess(() -> Component.literal("DreamingRecall: idle (recording defaults to off)."), false);
            return 1;
        }

        double seconds = status.durationNanos() / 1_000_000_000.0;
        double queuedMiB = status.metrics().queuedBytes() / (1024.0 * 1024.0);
        String line = String.format(
                Locale.ROOT,
                "DreamingRecall: %s/%s, %.1fs, chunks=%d (+%d baseline), queue=%d/%.2f MiB, segments=%d, dropped=%d core + %d optional, capture p95=%.3f ms p99=%.3f ms%s",
                status.mode(),
                status.pipelineState(),
                seconds,
                status.observedChunks(),
                status.pendingBaselineChunks(),
                status.metrics().queueDepth(),
                queuedMiB,
                status.metrics().committedSegments(),
                status.metrics().droppedCoreRecords(),
                status.metrics().droppedEnhancementRecords(),
                status.tickCost().p95Nanos() / 1_000_000.0,
                status.tickCost().p99Nanos() / 1_000_000.0,
                status.baselineRunning() ? ", baseline active" : ""
        );
        source.sendSuccess(() -> Component.literal(line), false);
        if (status.archiveDirectory() != null) {
            source.sendSuccess(() -> Component.literal("Archive: " + status.archiveDirectory()), false);
        }
        return 1;
    }

    private static int archives(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        Path root = DreamingRecallServer.INSTANCE.archiveRoot(server);
        CompletableFuture
                .supplyAsync(() -> readArchiveSummaries(root), commandExecutor())
                .whenComplete((summaries, failure) -> server.execute(() -> {
                    if (failure != null) {
                        source.sendFailure(Component.literal("Could not read replay archives: " + failure.getMessage()));
                        return;
                    }
                    if (summaries.isEmpty()) {
                        source.sendSuccess(() -> Component.literal("No DreamingRecall archives exist for this world."), false);
                        return;
                    }
                    source.sendSuccess(() -> Component.literal("DreamingRecall archives (newest first):"), false);
                    for (String summary : summaries) {
                        source.sendSuccess(() -> Component.literal(summary), false);
                    }
                }));
        return 1;
    }

    private static List<String> readArchiveSummaries(Path root) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (var directories = Files.list(root)) {
            return directories
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .limit(20)
                    .map(DreamingRecallCommands::archiveSummary)
                    .toList();
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String archiveSummary(Path archive) {
        try {
            ArchiveManifest manifest = ArchiveManifestCodec.readManifest(archive);
            boolean complete = Files.isRegularFile(archive.resolve(ArchiveManifestCodec.COMPLETION_FILE));
            return "%s  %s  %s  %s".formatted(
                    archive.getFileName(),
                    manifest.sourceKind(),
                    manifest.archiveId(),
                    complete ? "complete" : "recoverable/unclosed"
            );
        } catch (IOException failure) {
            return archive.getFileName() + "  unreadable manifest: " + failure.getMessage();
        }
    }

    private static java.util.concurrent.Executor commandExecutor() {
        return command -> Thread.ofVirtual().name("DreamingRecall-ArchiveCommand").start(command);
    }

    private static int setAnnounce(CommandSourceStack source, boolean enabled) {
        DreamingRecallConfig.ANNOUNCE_RECORDING.set(enabled);
        DreamingRecallConfig.SPEC.save();
        return success(source, "Recording announcements are now " + enabled + ".");
    }

    private static int setAutoRecording(CommandSourceStack source, boolean enabled) {
        DreamingRecallConfig.AUTO_RECORDING.set(enabled);
        DreamingRecallConfig.SPEC.save();
        return success(source, "Automatic recording is now " + enabled + "; it applies on the next server start.");
    }

    private static int setCaptureChat(CommandSourceStack source, boolean enabled) {
        DreamingRecallConfig.CAPTURE_CHAT.set(enabled);
        DreamingRecallConfig.SPEC.save();
        return success(source, "Chat capture is now " + enabled + ".");
    }

    private static int setAutomaticQuota(CommandSourceStack source, int mebibytes) {
        int max = 1024 * 1024;
        if (mebibytes > max) {
            return failure(source, "automaticQuotaMiB must be at most " + max + ".");
        }
        DreamingRecallConfig.AUTOMATIC_QUOTA_MEBIBYTES.set(mebibytes);
        DreamingRecallConfig.SPEC.save();
        return success(source, "Automatic archive quota is now " + mebibytes + " MiB (0 disables rotation).");
    }

    private static int setClientCameraTracks(CommandSourceStack source, boolean enabled) {
        DreamingRecallConfig.CLIENT_CAMERA_TRACKS_ALLOWED.set(enabled);
        DreamingRecallConfig.SPEC.save();
        return success(source, "Client camera track uploads are now " + enabled + ".");
    }

    private static int success(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), true);
        return 1;
    }

    private static int failure(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }
}
