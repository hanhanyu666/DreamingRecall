package com.hhy.dreamingrecall.playback.state;

import java.util.Objects;

public record ReplayDiagnostic(
        Severity severity,
        long archiveNanos,
        int typeId,
        String dimensionId,
        String message
) {
    public ReplayDiagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(message, "message");
    }

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }
}
