package com.hhy.dreamingrecall.archive;

import java.util.List;
import java.util.Map;

public record ArchiveInspection(
        int validSegments,
        long validRecords,
        long durationNanos,
        Map<Integer, Long> recordsByType,
        List<ArchiveDiagnostic> diagnostics
) {
    public ArchiveInspection {
        recordsByType = Map.copyOf(recordsByType);
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean isHealthy() {
        return diagnostics.stream().noneMatch(diagnostic -> diagnostic.severity() == ArchiveDiagnostic.Severity.ERROR);
    }
}
