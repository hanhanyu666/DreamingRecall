package com.hhy.dreamingrecall.archive;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Applies opt-in retention rules without ever deleting manual archives. */
public final class ArchiveRetentionManager {
    private ArchiveRetentionManager() {
    }

    public static RetentionResult enforce(Path archiveRoot, Policy policy) throws IOException {
        Path root = archiveRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return new RetentionResult(0, 0, 0, false);
        }
        long automaticBytes = 0;
        ArrayList<AutomaticArchive> candidates = new ArrayList<>();
        try (var stream = Files.list(root)) {
            for (Path candidate : stream.filter(Files::isDirectory).toList()) {
                if (Files.isSymbolicLink(candidate)) {
                    continue;
                }
                ArchiveManifest manifest;
                try {
                    manifest = ArchiveManifestCodec.readManifest(candidate);
                } catch (IOException ignored) {
                    continue;
                }
                if (!manifest.automatic()) {
                    continue;
                }
                long size = directorySize(candidate);
                automaticBytes = Math.addExact(automaticBytes, size);
                candidates.add(new AutomaticArchive(candidate, manifest.createdEpochMillis(), size));
            }
        }
        candidates.sort(Comparator.comparingLong(AutomaticArchive::createdEpochMillis));

        long deletedBytes = 0;
        int deletedArchives = 0;
        boolean lowDisk = belowFreeSpace(root, policy);
        for (AutomaticArchive candidate : candidates) {
            boolean overQuota = policy.maxAutomaticBytes() > 0
                    && automaticBytes > policy.maxAutomaticBytes();
            if (!overQuota && !lowDisk) {
                break;
            }
            deleteArchive(root, candidate.path());
            automaticBytes -= candidate.bytes();
            deletedBytes += candidate.bytes();
            deletedArchives++;
            lowDisk = belowFreeSpace(root, policy);
        }
        return new RetentionResult(deletedArchives, deletedBytes, automaticBytes, lowDisk);
    }

    private static boolean belowFreeSpace(Path root, Policy policy) throws IOException {
        if (policy.minimumFreeBytes() <= 0 && policy.minimumFreeFraction() <= 0.0) {
            return false;
        }
        FileStore store = Files.getFileStore(root);
        long usable = store.getUsableSpace();
        if (usable < policy.minimumFreeBytes()) {
            return true;
        }
        if (policy.minimumFreeFraction() <= 0.0) {
            return false;
        }
        long total = store.getTotalSpace();
        return total > 0 && (double) usable / (double) total < policy.minimumFreeFraction();
    }

    private static long directorySize(Path directory) throws IOException {
        try (var walk = Files.walk(directory)) {
            return walk.filter(path -> !Files.isSymbolicLink(path) && Files.isRegularFile(path))
                    .mapToLong(path -> {
                        try {
                            return Files.size(path);
                        } catch (IOException failure) {
                            return 0;
                        }
                    })
                    .sum();
        }
    }

    private static void deleteArchive(Path root, Path archive) throws IOException {
        Path normalized = archive.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root) || Files.isSymbolicLink(normalized)) {
            throw new IOException("Refusing to delete an archive outside the configured root");
        }
        try (var walk = Files.walk(normalized)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                if (Files.isSymbolicLink(path)) {
                    Files.deleteIfExists(path);
                } else {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    public record Policy(long maxAutomaticBytes, long minimumFreeBytes, double minimumFreeFraction) {
        public Policy {
            if (maxAutomaticBytes < 0 || minimumFreeBytes < 0
                    || !Double.isFinite(minimumFreeFraction)
                    || minimumFreeFraction < 0.0 || minimumFreeFraction > 1.0) {
                throw new IllegalArgumentException("Invalid archive retention policy");
            }
        }

        public static Policy disabled() {
            return new Policy(0, 0, 0.0);
        }
    }

    public record RetentionResult(
            int deletedArchives,
            long deletedBytes,
            long remainingAutomaticBytes,
            boolean stillBelowFreeSpace
    ) {
    }

    private record AutomaticArchive(Path path, long createdEpochMillis, long bytes) {
    }
}
