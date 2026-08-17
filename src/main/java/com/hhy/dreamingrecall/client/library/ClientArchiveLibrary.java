package com.hhy.dreamingrecall.client.library;

import com.hhy.dreamingrecall.archive.ArchiveDiagnostic;
import com.hhy.dreamingrecall.archive.ArchiveManifestCodec;
import com.hhy.dreamingrecall.archive.ArchiveScanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class ClientArchiveLibrary {
    private ClientArchiveLibrary() {
    }

    public static Path importedArchiveRoot(Path gameDirectory) {
        return gameDirectory.toAbsolutePath().normalize().resolve("dreamingrecall").resolve("replays");
    }

    public static ClientArchiveScan scan(Path gameDirectory) {
        Path normalized = gameDirectory.toAbsolutePath().normalize();
        ArrayList<ClientArchiveEntry> archives = new ArrayList<>();
        ArrayList<String> errors = new ArrayList<>();
        Set<Path> visited = new HashSet<>();

        scanRoot(importedArchiveRoot(normalized), "Imported", visited, archives, errors);
        Path saves = normalized.resolve("saves");
        if (Files.isDirectory(saves)) {
            try (var worlds = Files.list(saves)) {
                worlds.filter(Files::isDirectory).forEach(world -> scanRoot(
                        world.resolve("dreamingrecall").resolve("replays"),
                        world.getFileName().toString(),
                        visited,
                        archives,
                        errors
                ));
            } catch (IOException failure) {
                errors.add("Could not scan singleplayer saves: " + failure.getMessage());
            }
        }

        archives.sort(Comparator.comparingLong(
                (ClientArchiveEntry entry) -> entry.manifest().createdEpochMillis()
        ).reversed());
        return new ClientArchiveScan(archives, errors);
    }

    private static void scanRoot(
            Path root,
            String sourceLabel,
            Set<Path> visited,
            List<ClientArchiveEntry> archives,
            List<String> errors
    ) {
        Path normalized = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            return;
        }
        try (var candidates = Files.list(normalized)) {
            candidates.filter(Files::isDirectory).forEach(candidate -> {
                Path archive = candidate.toAbsolutePath().normalize();
                if (!visited.add(archive)) {
                    return;
                }
                try {
                    var manifest = ArchiveManifestCodec.readManifest(archive);
                    var scan = ArchiveScanner.scan(archive, false);
                    long duration = scan.index().segments().stream()
                            .mapToLong(segment -> segment.endArchiveNanos())
                            .max()
                            .orElse(0);
                    int errorCount = (int) scan.diagnostics().stream()
                            .filter(diagnostic -> diagnostic.severity() == ArchiveDiagnostic.Severity.ERROR)
                            .count();
                    int warningCount = (int) scan.diagnostics().stream()
                            .filter(diagnostic -> diagnostic.severity() == ArchiveDiagnostic.Severity.WARNING)
                            .count();
                    archives.add(new ClientArchiveEntry(
                            archive,
                            manifest,
                            sourceLabel,
                            Files.isRegularFile(archive.resolve(ArchiveManifestCodec.COMPLETION_FILE)),
                            duration,
                            scan.index().segments().size(),
                            errorCount,
                            warningCount
                    ));
                } catch (IOException | RuntimeException failure) {
                    errors.add(archive.getFileName() + ": " + failure.getMessage());
                }
            });
        } catch (IOException failure) {
            errors.add("Could not scan " + normalized + ": " + failure.getMessage());
        }
    }
}
