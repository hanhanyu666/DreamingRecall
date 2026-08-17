package com.hhy.dreamingrecall.archive;

import java.util.List;

public record ArchiveScanResult(ArchiveIndex index, List<ArchiveDiagnostic> diagnostics) {
    public ArchiveScanResult {
        diagnostics = List.copyOf(diagnostics);
    }
}
