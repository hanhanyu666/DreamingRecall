package com.hhy.dreamingrecall.archive;

public final class ArchiveFormat {
    public static final int FORMAT_MAJOR = 2;
    public static final int FORMAT_MINOR = 0;
    public static final int SEGMENT_FORMAT_MAJOR = 2;
    public static final int SEGMENT_FORMAT_MINOR = 0;
    public static final int SEGMENT_MAGIC = 0x44525347; // DRSG
    public static final int SEGMENT_HEADER_BYTES = 49;
    public static final String SEGMENT_EXTENSION = ".drseg";
    public static final String PARTIAL_EXTENSION = ".partial";
    public static final int MAX_DIMENSION_ID_BYTES = 4 * 1024;
    public static final int MAX_TRACK_ID_BYTES = 4 * 1024;
    public static final int MAX_PACKET_TYPE_ID_BYTES = 4 * 1024;
    public static final int MAX_NAMESPACE_BYTES = 1024;
    public static final int MAX_REGISTRY_FINGERPRINT_BYTES = 256;
    public static final int MAX_RECORD_BYTES = 64 * 1024 * 1024;
    public static final int MAX_SEGMENT_BYTES = 512 * 1024 * 1024;

    private ArchiveFormat() {
    }

    public static String segmentFileName(long sequence) {
        return "%08d%s".formatted(sequence, SEGMENT_EXTENSION);
    }

    public static String partialFileName(long sequence) {
        return "%08d%s".formatted(sequence, PARTIAL_EXTENSION);
    }
}
