package com.hhy.dreamingrecall.tools;

import com.hhy.dreamingrecall.archive.ArchiveInspection;
import com.hhy.dreamingrecall.archive.ArchiveInspector;

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
        inspection.diagnostics().forEach(diagnostic -> System.out.println(
                "  " + diagnostic.severity() + " " + diagnostic.path() + ": " + diagnostic.message()
        ));
        if (!inspection.isHealthy()) {
            throw new IllegalStateException("Archive inspection found errors");
        }
    }
}
