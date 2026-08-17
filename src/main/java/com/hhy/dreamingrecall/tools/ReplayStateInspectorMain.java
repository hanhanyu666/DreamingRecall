package com.hhy.dreamingrecall.tools;

import com.hhy.dreamingrecall.playback.source.LocalArchiveDataSource;
import com.hhy.dreamingrecall.playback.state.ReplayStateMaterializer;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

public final class ReplayStateInspectorMain {
    private ReplayStateInspectorMain() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 1 || arguments.length > 3) {
            throw new IllegalArgumentException(
                    "Usage: ReplayStateInspectorMain <archive-directory> [minecraft-version] [time-seconds]"
            );
        }
        String minecraftVersion = arguments.length >= 2 ? arguments[1] : "1.21.1";
        try (LocalArchiveDataSource source = LocalArchiveDataSource.open(Path.of(arguments[0]), minecraftVersion);
             ReplayStateMaterializer materializer = new ReplayStateMaterializer(source)) {
            var index = materializer.buildIndex().get(5, TimeUnit.MINUTES);
            long requestedNanos = arguments.length == 3
                    ? Math.round(Double.parseDouble(arguments[2]) * 1_000_000_000.0)
                    : index.durationNanos();
            var snapshot = materializer.seek(requestedNanos).get(5, TimeUnit.MINUTES);
            int chunks = snapshot.dimensions().values().stream().mapToInt(value -> value.chunks().size()).sum();
            int entities = snapshot.dimensions().values().stream().mapToInt(value -> value.entities().size()).sum();
            int players = snapshot.dimensions().values().stream().mapToInt(value -> value.players().size()).sum();
            System.out.println("Duration seconds: " + index.durationNanos() / 1_000_000_000.0);
            System.out.println("Checkpoints: " + index.checkpoints().size());
            System.out.println("Dimensions: " + snapshot.dimensions().size());
            System.out.println("Chunks: " + chunks);
            System.out.println("Entities: " + entities);
            System.out.println("Players: " + players);
            System.out.println("Recent chat: " + snapshot.recentChat().size());
            System.out.println("Recording gaps: " + snapshot.gaps().size());
            System.out.println("Diagnostics: " + snapshot.diagnostics().size());
            snapshot.dimensions().forEach((dimensionId, dimension) -> dimension.players().values().forEach(player -> {
                System.out.println("Player " + player.name() + " @ " + dimensionId + ":");
                System.out.println(
                        "  position=" + player.transform().x() + "," + player.transform().y() + "," + player.transform().z()
                                + " yaw=" + player.transform().yaw() + " pitch=" + player.transform().pitch()
                );
                System.out.println("  eye=" + player.eyeX() + "," + player.eyeY() + "," + player.eyeZ());
                System.out.println("  selectedSlot=" + player.selectedSlot());
                player.equipment().forEach(entry -> System.out.println(
                        "  " + entry.slot() + "=" + entry.stack().itemId() + " x" + entry.stack().count()
                ));
                player.animation().ifPresent(animation -> System.out.println(
                        "  animation attack=" + animation.attackProgress()
                                + " swinging=" + animation.swinging()
                                + " swingTime=" + animation.swingTime()
                                + " hand=" + animation.swingingArm()
                                + " using=" + animation.usingItem()
                                + " usedHand=" + animation.usedItemHand()
                                + " remaining=" + animation.useItemRemainingTicks()
                ));
            }));
            snapshot.dimensions().forEach((dimensionId, dimension) -> dimension.cameraSamples().values().forEach(camera ->
                    System.out.println(
                            "Camera " + camera.playerId() + " @ " + dimensionId
                                    + ": position=" + camera.x() + "," + camera.y() + "," + camera.z()
                                    + " yaw=" + camera.yaw() + " pitch=" + camera.pitch()
                                    + " roll=" + camera.roll() + " fov=" + camera.fov()
                    )
            ));
            snapshot.diagnostics().forEach(diagnostic -> System.out.println(
                    "  " + diagnostic.severity()
                            + " @ " + diagnostic.archiveNanos()
                            + " type=" + diagnostic.typeId()
                            + " dimension=" + diagnostic.dimensionId()
                            + ": " + diagnostic.message()
            ));
        }
    }
}
