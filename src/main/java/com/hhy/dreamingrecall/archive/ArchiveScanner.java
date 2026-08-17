package com.hhy.dreamingrecall.archive;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class ArchiveScanner {
    private ArchiveScanner() {
    }

    public static ArchiveScanResult scan(Path archiveDirectory, boolean verifyPayloads) throws IOException {
        return scanSegmentDirectory(archiveDirectory.resolve("segments"), verifyPayloads);
    }

    public static ArchiveScanResult scanSegmentDirectory(Path segmentsDirectory, boolean verifyPayloads)
            throws IOException {
        if (!Files.isDirectory(segmentsDirectory)) {
            return new ArchiveScanResult(new ArchiveIndex(List.of()), List.of());
        }

        ArrayList<Path> candidates = new ArrayList<>();
        ArrayList<ArchiveDiagnostic> diagnostics = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(segmentsDirectory)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                if (name.endsWith(ArchiveFormat.SEGMENT_EXTENSION)) {
                    candidates.add(path);
                } else if (name.endsWith(ArchiveFormat.PARTIAL_EXTENSION)) {
                    diagnostics.add(new ArchiveDiagnostic(
                            ArchiveDiagnostic.Severity.WARNING,
                            path,
                            "Ignored an uncommitted segment tail"
                    ));
                }
            }
        }
        candidates.sort(Comparator.comparing(path -> path.getFileName().toString()));

        ArrayList<SegmentMetadata> valid = new ArrayList<>();
        long expectedSequence = 0;
        for (Path candidate : candidates) {
            try {
                SegmentMetadata metadata = verifyPayloads
                        ? SegmentCodec.read(candidate).metadata()
                        : SegmentCodec.readMetadata(candidate);
                if (metadata.sequence() != expectedSequence) {
                    diagnostics.add(new ArchiveDiagnostic(
                            ArchiveDiagnostic.Severity.WARNING,
                            candidate,
                            "Sequence discontinuity: expected " + expectedSequence + " but found " + metadata.sequence()
                    ));
                }
                expectedSequence = metadata.sequence() + 1;
                valid.add(metadata);
            } catch (IOException failure) {
                diagnostics.add(new ArchiveDiagnostic(
                        ArchiveDiagnostic.Severity.ERROR,
                        candidate,
                        failure.getMessage()
                ));
            }
        }
        return new ArchiveScanResult(new ArchiveIndex(valid), diagnostics);
    }
}
