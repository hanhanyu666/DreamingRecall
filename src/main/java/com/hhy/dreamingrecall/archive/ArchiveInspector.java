package com.hhy.dreamingrecall.archive;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ArchiveInspector {
    private ArchiveInspector() {
    }

    public static ArchiveInspection inspect(Path archiveDirectory) throws IOException {
        ArchiveManifestCodec.readManifest(archiveDirectory);
        ArchiveScanResult scan = ArchiveScanner.scan(archiveDirectory, false);
        ArrayList<ArchiveDiagnostic> diagnostics = new ArrayList<>(scan.diagnostics());
        LinkedHashMap<Integer, Long> recordsByType = new LinkedHashMap<>();
        int validSegments = 0;
        long validRecords = 0;
        long durationNanos = 0;
        long previousArchiveNanos = -1;
        ContentAddressedStore contentStore = new ContentAddressedStore(archiveDirectory, 1);

        for (SegmentMetadata metadata : scan.index().segments()) {
            try {
                SegmentReadResult segment = SegmentCodec.read(metadata.path());
                validSegments++;
                for (ReplayRecord record : segment.records()) {
                    if (record.archiveNanos() < previousArchiveNanos) {
                        diagnostics.add(new ArchiveDiagnostic(
                                ArchiveDiagnostic.Severity.ERROR,
                                metadata.path(),
                                "Record time moved backwards from " + previousArchiveNanos + " to " + record.archiveNanos()
                        ));
                    }
                    previousArchiveNanos = Math.max(previousArchiveNanos, record.archiveNanos());
                    durationNanos = Math.max(durationNanos, record.archiveNanos());
                    recordsByType.merge(record.typeId(), 1L, Long::sum);
                    var envelope = record.typeId() == CoreRecordType.CHUNK_BASELINE.id()
                            ? ContentReferenceCodec.decodeEnvelope(record.payloadCopy())
                            : java.util.Optional.<ContentReferenceEnvelope>empty();
                    if (envelope.isPresent()) {
                        try {
                            contentStore.read(envelope.get().reference());
                        } catch (IOException contentFailure) {
                            diagnostics.add(new ArchiveDiagnostic(
                                    ArchiveDiagnostic.Severity.ERROR,
                                    metadata.path(),
                                    "Missing or corrupt content "
                                            + envelope.get().reference().hexHash()
                                            + "; playback will use its embedded placeholder"
                            ));
                        }
                    }
                    validRecords++;
                }
            } catch (IOException failure) {
                diagnostics.add(new ArchiveDiagnostic(
                        ArchiveDiagnostic.Severity.ERROR,
                        metadata.path(),
                        failure.getMessage()
                ));
            }
        }
        return new ArchiveInspection(validSegments, validRecords, durationNanos, recordsByType, diagnostics);
    }

    public static Map<String, Long> namedRecordCounts(ArchiveInspection inspection) {
        LinkedHashMap<String, Long> names = new LinkedHashMap<>();
        inspection.recordsByType().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> names.put(
                        CoreRecordType.fromId(entry.getKey()).map(Enum::name).orElse("UNKNOWN_" + entry.getKey()),
                        entry.getValue()
                ));
        return names;
    }
}
