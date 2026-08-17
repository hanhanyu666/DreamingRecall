package com.hhy.dreamingrecall.tools;

import com.hhy.dreamingrecall.archive.ArchiveInspection;
import com.hhy.dreamingrecall.archive.ArchiveInspector;
import com.hhy.dreamingrecall.archive.ArchiveManifestCodec;
import com.hhy.dreamingrecall.client.playback.packet.PacketReplayIndexer;
import com.hhy.dreamingrecall.playback.source.LocalArchiveDataSource;
import com.hhy.dreamingrecall.playback.source.ReadCancellation;

import java.nio.file.Path;

public final class ArchiveInspectorMain {
    private ArchiveInspectorMain() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Usage: ArchiveInspectorMain <archive-directory>");
        }
        Path archive = Path.of(arguments[0]).toAbsolutePath().normalize();
        ArchiveInspection inspection = ArchiveInspector.inspect(archive);
        System.out.println("Archive: " + archive);
        System.out.println("Healthy: " + inspection.isHealthy());
        System.out.println("Segments: " + inspection.validSegments());
        System.out.println("Records: " + inspection.validRecords());
        System.out.println("Duration seconds: " + inspection.durationNanos() / 1_000_000_000.0);
        ArchiveInspector.namedRecordCounts(inspection)
                .forEach((type, count) -> System.out.println("  " + type + ": " + count));
        var manifest = ArchiveManifestCodec.readManifest(archive);
        try (LocalArchiveDataSource source = LocalArchiveDataSource.open(archive, manifest.minecraftVersion())) {
            var packetIndex = PacketReplayIndexer.scan(source, new ReadCancellation());
            System.out.println("Playable exact player tracks: " + packetIndex.playablePlayers().size());
            packetIndex.playablePlayers().forEach(playerId -> {
                var track = packetIndex.track(playerId).orElseThrow();
                System.out.println(
                        "  " + playerId
                                + ": " + track.packetCount() + " frames, world starts at "
                                + track.worldStartNanos() + " ns"
                );
            });
        }
        inspection.diagnostics().forEach(diagnostic -> System.out.println(
                "  " + diagnostic.severity() + " " + diagnostic.path() + ": " + diagnostic.message()
        ));
        if (!inspection.isHealthy()) {
            throw new IllegalStateException("Archive inspection found errors");
        }
    }
}
