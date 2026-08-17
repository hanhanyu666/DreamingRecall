package com.hhy.dreamingrecall.archive;

import java.nio.file.Path;

public record ArchiveDiagnostic(Severity severity, Path path, String message) {
    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }
}
